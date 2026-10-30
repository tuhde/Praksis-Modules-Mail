// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Credentials;
import it.uhde.praksis.mail.html.Sanitizer;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;

/**
 * The three capabilities that only read: {@code mail.folders} (every folder with its unread and its
 * total number of mails), {@code mail.list} (the newest mails of a folder, with what a list shows)
 * and {@code mail.get} (one mail for showing: headers, plain text, HTML as text and the list of
 * attachments). Nothing is changed on the server, the mails are fetched with {@code peek} so they
 * stay unread, and content that could do something is never executed here.
 */
public abstract class ImapRead implements Capability {
  private static final System.Logger LOG = System.getLogger(ImapRead.class.getName());
  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 1000;

  /** Sorting by a header needs every envelope, so it is only done up to this many mails. */
  static final int MAX_SORTED = 5000;

  /** The attachment size that is returned unless the call asks for more. */
  static final long DEFAULT_ATTACHMENT = 5L * 1024 * 1024;

  /** The most characters of a text or HTML body that are returned. */
  private static final int MAX_BODY = 2_000_000;

  /** One account of the module, with its connection and its cache. */
  private static final class State {
    final String name;
    final ImapSettings settings;
    final Connection connection;
    HeaderCache cache;

    State(String name, ImapSettings settings) {
      this.name = name;
      this.settings = settings;
      this.connection = new Connection(settings, name);
    }
  }

  private final Map<String, State> accounts = new java.util.LinkedHashMap<>();
  private State current;

  // The account of the call in progress. A call is made with the lock held, so these can be plain
  // fields, which is what the readers below use.
  private ImapSettings settings;
  Connection connection;

  /** The name of the account of the call in progress. */
  String account = Accounts.DEFAULT;

  /** Whether the call in progress is a dry run; set by {@link #invoke}, which is synchronized. */
  boolean dryRun;

  /** The headers of the account, or null if there is no data directory or the cache is off. */
  HeaderCache cache;

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    accounts.clear();
    Accounts.parse(config, ImapSettings::parse)
        .forEach((name, s) -> accounts.put(name, new State(name, s)));
  }

  @Override
  public synchronized void useCredentials(Credentials credentials) {
    accounts.values().forEach(a -> a.connection.useCredentials(credentials));
  }

  @Override
  public synchronized void useDataDirectory(java.nio.file.Path directory) {
    for (State a : accounts.values()) {
      if (a.settings.cache() && a.cache == null) {
        try {
          a.cache = HeaderCache.open(directory, a.settings, a.name);
        } catch (SQLException e) {
          // reading works without the cache, only slower
          LOG.log(System.Logger.Level.WARNING, "imap: no header cache: " + e.getMessage());
        }
      }
    }
  }

  @Override
  public synchronized void close() {
    for (State a : accounts.values()) {
      a.connection.close();
      if (a.cache != null) {
        a.cache.release();
        a.cache = null;
      }
    }
    cache = null;
  }

  /**
   * Chooses the account of a call: the one a mail id names, else the one in {@code account}, else
   * the only one.
   */
  private State pick(Map<String, Object> args) throws CapabilityException {
    if (args.get("message") instanceof String message) {
      MessageId id =
          MessageId.parse(message)
              .orElseThrow(
                  () ->
                      new CapabilityException(
                          "'" + message + "' is not the id of an IMAP mail", false));
      State state = accounts.get(id.account());
      if (state == null) {
        throw new CapabilityException(
            "the mail is of the account '"
                + id.account()
                + "', which is not set up; the accounts are "
                + accounts.keySet(),
            false);
      }
      return state;
    }
    return accounts.get(Accounts.choose(accounts, args.get("account")));
  }

  private void select(State state) {
    current = state;
    settings = state.settings;
    connection = state.connection;
    account = state.name;
    cache = state.cache;
  }

  /**
   * Brings a folder of the cache up to date unless it is trusted already.
   *
   * @return false if the folder does not exist
   */
  boolean refresh(IMAPFolder folder, String shown) throws MessagingException, SQLException {
    if (cache.fresh(shown)) {
      return true;
    }
    if (!folder.exists()) {
      return false;
    }
    folder.open(Folder.READ_ONLY);
    try {
      cache.sync(folder, shown);
    } finally {
      folder.close(false);
    }
    return true;
  }

  @Override
  public final synchronized Map<String, Object> invoke(
      CapabilityContext context, Map<String, Object> args) throws CapabilityException {
    if (accounts.isEmpty()) {
      throw new CapabilityException(name() + " is not configured", false);
    }
    select(pick(args));
    dryRun = context.dryRun();
    try {
      if (cache != null) {
        Map<String, Object> cached = fromCache(args, false);
        if (cached != null) {
          return cached;
        }
      }
      return read(connection.store(), args);
    } catch (SQLException e) {
      // a cache that fails is switched off; the call is answered by the server instead
      LOG.log(System.Logger.Level.WARNING, "imap: header cache failed: " + e.getMessage());
      cache.release();
      cache = null;
      current.cache = null;
      return invoke(context, args);
    } catch (CapabilityException e) {
      // a connection that cannot be made is retryable; anything else is an answer to the call
      Map<String, Object> offline = e.retryable() ? staleFromCache(args) : null;
      if (offline != null) {
        return offline;
      }
      throw e;
    } catch (MessagingException | IOException e) {
      connection.close(); // the next call opens a new connection
      Map<String, Object> offline = staleFromCache(args);
      if (offline != null) {
        return offline;
      }
      throw new CapabilityException("imap " + settings.account() + ": " + e.getMessage(), true, e);
    }
  }

  /** What the cache says when the server cannot be asked, or null if it knows nothing. */
  private Map<String, Object> staleFromCache(Map<String, Object> args) {
    try {
      return cache == null ? null : fromCache(args, true);
    } catch (SQLException | CapabilityException e) {
      return null;
    }
  }

  /**
   * The answer from the cache alone, without a connection, or null if the cache cannot give it.
   *
   * @param stale whether a folder that is not known to be up to date may be answered from, which is
   *     what is done when the server cannot be reached
   */
  Map<String, Object> fromCache(Map<String, Object> args, boolean stale)
      throws SQLException, CapabilityException {
    return null;
  }

  abstract Map<String, Object> read(IMAPStore store, Map<String, Object> args)
      throws MessagingException, IOException, CapabilityException, SQLException;

  /** The folder name as a rule writes it: levels separated by {@code /}. */
  static String display(IMAPFolder folder) throws MessagingException {
    char separator = folder.getSeparator();
    return separator == '/' || separator == 0
        ? folder.getFullName()
        : folder.getFullName().replace(separator, '/');
  }

  /** The folder name as the server wants it. */
  static String serverName(String name, IMAPFolder reference) throws MessagingException {
    char separator = reference.getSeparator();
    return separator == '/' || separator == 0 ? name : name.replace('/', separator);
  }

  /** {@code mail.folders}. */
  public static final class Folders extends ImapRead {
    @Override
    public String name() {
      return "mail.folders";
    }

    /** The cached numbers, or the server's (one STATUS), which are then kept for a while. */
    private HeaderCache.Counts counts(IMAPFolder folder, String shown) throws MessagingException {
      try {
        var known = cache.counts(shown);
        if (known.isPresent()) {
          return known.get();
        }
      } catch (SQLException e) {
        return null;
      }
      var fetched =
          new HeaderCache.Counts(
              Math.max(0, folder.getMessageCount()), Math.max(0, folder.getUnreadMessageCount()));
      cache.remember(shown, fetched);
      return fetched;
    }

    @Override
    Map<String, Object> read(IMAPStore store, Map<String, Object> args) throws MessagingException {
      List<Object> folders = new ArrayList<>();
      IMAPFolder root = (IMAPFolder) store.getDefaultFolder();
      for (Folder f : root.list("*")) {
        IMAPFolder folder = (IMAPFolder) f;
        if ((folder.getType() & Folder.HOLDS_MESSAGES) == 0) {
          continue;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        String shown = display(folder);
        HeaderCache.Counts counts = cache == null ? null : counts(folder, shown);
        entry.put("name", shown);
        entry.put(
            "unread",
            counts != null ? counts.unread() : (long) Math.max(0, folder.getUnreadMessageCount()));
        entry.put(
            "total",
            counts != null ? counts.total() : (long) Math.max(0, folder.getMessageCount()));
        folders.add(entry);
      }
      folders.sort(
          (a, b) -> {
            String x = (String) ((Map<?, ?>) a).get("name");
            String y = (String) ((Map<?, ?>) b).get("name");
            if (x.equalsIgnoreCase("INBOX") != y.equalsIgnoreCase("INBOX")) {
              return x.equalsIgnoreCase("INBOX") ? -1 : 1;
            }
            return x.compareToIgnoreCase(y);
          });
      return Map.of("folders", folders);
    }
  }

  /** {@code mail.list}. */
  public static final class Listing extends ImapRead {
    @Override
    Map<String, Object> fromCache(Map<String, Object> args, boolean stale)
        throws SQLException, CapabilityException {
      String shown = cache.shown((String) args.get("folder"));
      if (stale ? cache.validity(shown) == null : !cache.fresh(shown)) {
        return null;
      }
      long asked = args.get("limit") instanceof Number n ? n.longValue() : DEFAULT_LIMIT;
      int limit = (int) Math.max(1, Math.min(asked, MAX_LIMIT));
      int offset = args.get("offset") instanceof Number n ? (int) Math.max(0, n.longValue()) : 0;
      String sort = args.get("sort") instanceof String t ? t : "time";
      if (!List.of("time", "from", "subject", "size").contains(sort)) {
        throw new CapabilityException("sort must be time, from, subject or size", false);
      }
      return Map.of(
          "items",
          new ArrayList<Object>(
              cache.list(
                  shown,
                  sort,
                  Boolean.TRUE.equals(args.get("reverse")),
                  offset,
                  limit,
                  Boolean.TRUE.equals(args.get("threaded")))),
          "count",
          cache.total(shown));
    }

    @Override
    public String name() {
      return "mail.list";
    }

    @Override
    Map<String, Object> read(IMAPStore store, Map<String, Object> args)
        throws MessagingException, CapabilityException, SQLException {
      IMAPFolder folder = (IMAPFolder) store.getFolder((String) args.get("folder"));
      long asked = args.get("limit") instanceof Number n ? n.longValue() : DEFAULT_LIMIT;
      int limit = (int) Math.max(1, Math.min(asked, MAX_LIMIT));
      int offset = args.get("offset") instanceof Number n ? (int) Math.max(0, n.longValue()) : 0;
      String sort = args.get("sort") instanceof String t ? t : "time";
      boolean reverse = Boolean.TRUE.equals(args.get("reverse"));
      if (!List.of("time", "from", "subject", "size").contains(sort)) {
        throw new CapabilityException("sort must be time, from, subject or size", false);
      }
      if (cache != null) {
        String shown = display(folder);
        if (!refresh(folder, shown)) {
          return Map.of("items", List.of(), "count", 0L);
        }
        return Map.of(
            "items",
            new ArrayList<Object>(
                cache.list(
                    shown,
                    sort,
                    reverse,
                    offset,
                    limit,
                    Boolean.TRUE.equals(args.get("threaded")))),
            "count",
            cache.total(shown));
      }
      if (!folder.exists()) {
        return Map.of("items", List.of(), "count", 0L);
      }
      boolean threaded = Boolean.TRUE.equals(args.get("threaded"));
      folder.open(Folder.READ_ONLY);
      try {
        int total = folder.getMessageCount();
        List<Object> items = new ArrayList<>();
        if (total > 0 && sort.equals("time")) {
          // the sequence number grows with the arrival, so the window is a range of numbers
          int first;
          int last;
          if (reverse) {
            first = 1 + offset;
            last = Math.min(total, first + limit - 1);
          } else {
            last = total - offset;
            first = Math.max(1, last - limit + 1);
          }
          if (first <= last) {
            Message[] messages = folder.getMessages(first, last);
            fetch(folder, messages);
            for (int i = 0; i < messages.length; i++) {
              items.add(
                  item(
                      account,
                      folder,
                      (MimeMessage) messages[reverse ? i : messages.length - 1 - i],
                      threaded));
            }
          }
        } else if (total > 0) {
          if (total > MAX_SORTED) {
            throw new CapabilityException(
                "sorting by "
                    + sort
                    + " works up to "
                    + MAX_SORTED
                    + " mails, the folder has "
                    + total,
                false);
          }
          Message[] messages = folder.getMessages();
          fetch(folder, messages);
          List<MimeMessage> all = new ArrayList<>();
          for (Message m : messages) {
            all.add((MimeMessage) m);
          }
          Comparator<MimeMessage> order =
              sort.equals("size")
                  ? Comparator.comparingInt(Listing::size)
                  : Comparator.comparing(m -> text(m, sort));
          all.sort(
              (x, y) -> {
                int c = order.compare(x, y);
                return reverse ? -c : c;
              });
          int from = Math.min(offset, all.size());
          for (MimeMessage m : all.subList(from, Math.min(from + limit, all.size()))) {
            items.add(item(account, folder, m, threaded));
          }
        }
        if (threaded) {
          // the mails of a conversation together, within the page that was asked for
          @SuppressWarnings("unchecked")
          List<Map<String, Object>> page = (List<Map<String, Object>>) (List<?>) items;
          items = new ArrayList<>(it.uhde.praksis.mail.html.Threads.arrange(page));
        }
        return Map.of("items", items, "count", (long) total);
      } finally {
        folder.close(false);
      }
    }

    private static int size(MimeMessage m) {
      try {
        return m.getSize();
      } catch (MessagingException e) {
        return 0;
      }
    }

    private static String text(MimeMessage m, String sort) {
      try {
        return (sort.equals("from") ? address(m.getFrom()) : subject(m)).toLowerCase(Locale.ROOT);
      } catch (MessagingException e) {
        return "";
      }
    }

    static Map<String, Object> item(String account, IMAPFolder folder, MimeMessage m)
        throws MessagingException {
      return item(account, folder, m, false);
    }

    /** As above; for conversations also the headers that say which mail answers which. */
    static Map<String, Object> item(
        String account, IMAPFolder folder, MimeMessage m, boolean threadHeaders)
        throws MessagingException {
      Map<String, Object> item = new LinkedHashMap<>();
      String[] ids = m.getHeader("Message-ID");
      item.put(
          "id",
          new MessageId(
                  account,
                  display(folder),
                  folder.getUIDValidity(),
                  folder.getUID(m),
                  ids == null || ids.length == 0 ? "" : ids[0])
              .encode());
      item.put("from", address(m.getFrom()));
      item.put("subject", subject(m));
      item.put("date", m.getHeader("Date") == null ? "" : m.getHeader("Date")[0]);
      item.put("time", instant(m));
      item.put("size", (long) Math.max(0, m.getSize()));
      item.put("unread", !m.getFlags().contains(Flags.Flag.SEEN));
      item.put("starred", m.getFlags().contains(Flags.Flag.FLAGGED));
      if (threadHeaders) {
        item.put("message_id", ids == null || ids.length == 0 ? "" : ids[0]);
        item.put("in_reply_to", ImapSource.header(m, "In-Reply-To"));
        item.put("references", ImapSource.header(m, "References"));
      }
      return item;
    }
  }

  /** The mail an argument names. */
  static MessageId id(Map<String, Object> args) throws CapabilityException {
    return MessageId.parse((String) args.get("message"))
        .orElseThrow(
            () ->
                new CapabilityException(
                    "'" + args.get("message") + "' is not the id of an IMAP mail", false));
  }

  /** {@code mail.search}: the mails of a folder with a text in the sender, subject or body. */
  public static final class Search extends ImapRead {
    @Override
    public String name() {
      return "mail.search";
    }

    @Override
    Map<String, Object> fromCache(Map<String, Object> args, boolean stale)
        throws SQLException, CapabilityException {
      String query = (String) args.get("query");
      String in = args.get("in") instanceof String t ? t : "all";
      String shown = cache.shown((String) args.get("folder"));
      // the sender and the subject are in the cache; the text of the mails is not
      if (query.isEmpty()
          || !(in.equals("from") || in.equals("subject"))
          || (stale ? cache.validity(shown) == null : !cache.fresh(shown))) {
        return null;
      }
      long asked = args.get("limit") instanceof Number n ? n.longValue() : DEFAULT_LIMIT;
      int limit = (int) Math.max(1, Math.min(asked, MAX_LIMIT));
      return Map.of(
          "items",
          new ArrayList<Object>(cache.search(shown, query, in, limit)),
          "count",
          cache.searchCount(shown, query, in));
    }

    @Override
    Map<String, Object> read(IMAPStore store, Map<String, Object> args)
        throws MessagingException, CapabilityException, SQLException {
      IMAPFolder folder = (IMAPFolder) store.getFolder((String) args.get("folder"));
      String query = (String) args.get("query");
      String in = args.get("in") instanceof String t ? t : "all";
      jakarta.mail.search.SearchTerm term =
          switch (in) {
            case "from" -> new jakarta.mail.search.FromStringTerm(query);
            case "subject" -> new jakarta.mail.search.SubjectTerm(query);
            case "body" -> new jakarta.mail.search.BodyTerm(query);
            case "all" ->
                new jakarta.mail.search.OrTerm(
                    new jakarta.mail.search.SearchTerm[] {
                      new jakarta.mail.search.FromStringTerm(query),
                      new jakarta.mail.search.SubjectTerm(query),
                      new jakarta.mail.search.BodyTerm(query)
                    });
            default ->
                throw new CapabilityException("in must be all, from, subject or body", false);
          };
      long asked = args.get("limit") instanceof Number n ? n.longValue() : DEFAULT_LIMIT;
      int limit = (int) Math.max(1, Math.min(asked, MAX_LIMIT));
      if (cache != null && !query.isEmpty() && (in.equals("from") || in.equals("subject"))) {
        // the sender and the subject are in the cache; the text of the mails is not
        String shown = display(folder);
        if (!refresh(folder, shown)) {
          return Map.of("items", List.of(), "count", 0L);
        }
        return Map.of(
            "items",
            new ArrayList<Object>(cache.search(shown, query, in, limit)),
            "count",
            cache.searchCount(shown, query, in));
      }
      if (!folder.exists()) {
        return Map.of("items", List.of(), "count", 0L);
      }
      folder.open(Folder.READ_ONLY);
      try {
        Message[] hits = query.isEmpty() ? folder.getMessages() : folder.search(term);
        int from = Math.max(0, hits.length - limit);
        Message[] window = java.util.Arrays.copyOfRange(hits, from, hits.length);
        fetch(folder, window);
        List<Object> items = new ArrayList<>();
        for (int i = window.length - 1; i >= 0; i--) {
          items.add(Listing.item(account, folder, (MimeMessage) window[i]));
        }
        return Map.of("items", items, "count", (long) hits.length);
      } finally {
        folder.close(false);
      }
    }
  }

  /** {@code mail.attachment}: the content of one attachment, unless it is too big. */
  public static final class Attachment extends ImapRead {
    @Override
    public String name() {
      return "mail.attachment";
    }

    @Override
    Map<String, Object> read(IMAPStore store, Map<String, Object> args)
        throws MessagingException, IOException, CapabilityException {
      MessageId id = id(args);
      String wanted = (String) args.get("name");
      long max =
          args.get("max_bytes") instanceof Number n
              ? Math.max(1, Math.min(n.longValue(), 25L * 1024 * 1024))
              : DEFAULT_ATTACHMENT;
      IMAPFolder folder = (IMAPFolder) store.getFolder(id.folder());
      folder.open(Folder.READ_ONLY);
      try {
        MimeMessage m =
            folder.getUIDValidity() == id.validity()
                ? (MimeMessage) folder.getMessageByUID(id.uid())
                : null;
        if (m == null) {
          throw new CapabilityException("mail not found: it is not in " + id.folder(), false);
        }
        Part part = find(m, wanted, 0);
        if (part == null) {
          throw new CapabilityException("the mail has no attachment named '" + wanted + "'", false);
        }
        if (part.getSize() > max) {
          throw new CapabilityException(
              "the attachment '"
                  + wanted
                  + "' is "
                  + part.getSize()
                  + " bytes, more than the limit of "
                  + max,
              false);
        }
        byte[] bytes;
        try (java.io.InputStream in = part.getInputStream()) {
          bytes = in.readNBytes((int) max + 1);
        }
        if (bytes.length > max) {
          throw new CapabilityException(
              "the attachment '" + wanted + "' is more than the limit of " + max + " bytes", false);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", wanted);
        out.put("type", part.getContentType().split(";", 2)[0].strip().toLowerCase(Locale.ROOT));
        out.put("size", (long) bytes.length);
        out.put("content", bytes);
        return out;
      } finally {
        folder.close(false);
      }
    }

    private static Part find(Part part, String name, int depth)
        throws MessagingException, IOException {
      if (depth > 8) {
        return null;
      }
      if (part.isMimeType("multipart/*")) {
        Multipart multipart = (Multipart) part.getContent();
        for (int i = 0; i < multipart.getCount(); i++) {
          Part hit = find(multipart.getBodyPart(i), name, depth + 1);
          if (hit != null) {
            return hit;
          }
        }
        return null;
      }
      String file = part.getFileName();
      return file != null && MimeUtility.decodeText(file).equals(name) ? part : null;
    }
  }

  /** What a list needs of each mail, in one round trip. */
  static void fetch(IMAPFolder folder, Message[] messages) throws MessagingException {
    FetchProfile profile = new FetchProfile();
    profile.add(FetchProfile.Item.ENVELOPE);
    profile.add(FetchProfile.Item.FLAGS);
    profile.add(FetchProfile.Item.SIZE);
    profile.add(IMAPFolder.FetchProfileItem.HEADERS);
    profile.add(jakarta.mail.UIDFolder.FetchProfileItem.UID);
    folder.fetch(messages, profile);
  }

  /** {@code mail.get}. */
  public static final class Get extends ImapRead {
    @Override
    public String name() {
      return "mail.get";
    }

    @Override
    Map<String, Object> read(IMAPStore store, Map<String, Object> args)
        throws MessagingException, IOException, CapabilityException {
      MessageId id =
          MessageId.parse((String) args.get("message"))
              .orElseThrow(
                  () ->
                      new CapabilityException(
                          "'" + args.get("message") + "' is not the id of an IMAP mail", false));
      IMAPFolder folder = (IMAPFolder) store.getFolder(id.folder());
      folder.open(Folder.READ_ONLY);
      try {
        MimeMessage m =
            folder.getUIDValidity() == id.validity()
                ? (MimeMessage) folder.getMessageByUID(id.uid())
                : null;
        if (m == null) {
          throw new CapabilityException("mail not found: it is not in " + id.folder(), false);
        }
        Body body = new Body();
        collect(m, body, 0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", address(m.getFrom()));
        out.put("to", address(m.getRecipients(Message.RecipientType.TO)));
        out.put("cc", address(m.getRecipients(Message.RecipientType.CC)));
        out.put("subject", subject(m));
        out.put("date", m.getHeader("Date") == null ? "" : m.getHeader("Date")[0]);
        out.put("time", instant(m));
        out.put("account", account);
        out.put("message_id", id.messageId());
        out.put("reply_to", ImapSource.header(m, "Reply-To"));
        out.put("references", ImapSource.header(m, "References"));
        Sanitizer.Result safe =
            Sanitizer.sanitize(cut(body.html), Boolean.TRUE.equals(args.get("images")));
        // a mail with only HTML still has a text, which a reply quotes
        out.put(
            "text",
            body.text.isEmpty() && !safe.blocks().isEmpty()
                ? cut(Sanitizer.plain(safe.blocks()))
                : cut(body.text));
        out.put("html", cut(body.html));
        out.put("blocks", safe.blocks());
        out.put("blocked", (long) safe.blockedImages());
        out.put("attachments", body.attachments);
        out.put("size", (long) Math.max(0, m.getSize()));
        return out;
      } finally {
        folder.close(false);
      }
    }

    private static String cut(String text) {
      return text.length() > MAX_BODY ? text.substring(0, MAX_BODY) : text;
    }

    private static final class Body {
      String text = "";
      String html = "";
      final List<Object> attachments = new ArrayList<>();
    }

    /** Walks the parts: the first plain text, the first HTML, and the attachments by name. */
    private static void collect(Part part, Body out, int depth)
        throws MessagingException, IOException {
      if (depth > 8) {
        return;
      }
      if (part.isMimeType("multipart/*")) {
        Multipart multipart = (Multipart) part.getContent();
        for (int i = 0; i < multipart.getCount(); i++) {
          collect(multipart.getBodyPart(i), out, depth + 1);
        }
        return;
      }
      String disposition = part.getDisposition();
      String file = part.getFileName();
      boolean attached = Part.ATTACHMENT.equalsIgnoreCase(disposition) || file != null;
      if (!attached && part.isMimeType("text/plain")) {
        if (out.text.isEmpty()) {
          out.text = String.valueOf(part.getContent());
        }
      } else if (!attached && part.isMimeType("text/html")) {
        if (out.html.isEmpty()) {
          out.html = String.valueOf(part.getContent());
        }
      } else {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", file == null ? "(no name)" : MimeUtility.decodeText(file));
        a.put("type", part.getContentType().split(";", 2)[0].strip().toLowerCase());
        a.put("size", (long) Math.max(0, part.getSize()));
        out.attachments.add(a);
      }
    }
  }

  static String address(jakarta.mail.Address[] addresses) {
    if (addresses == null || addresses.length == 0) {
      return "";
    }
    List<String> out = new ArrayList<>();
    for (jakarta.mail.Address a : addresses) {
      out.add(a instanceof InternetAddress ia ? ia.toUnicodeString() : a.toString());
    }
    return String.join(", ", out);
  }

  static String subject(MimeMessage m) throws MessagingException {
    String s = m.getSubject();
    return s == null ? "" : s;
  }

  /** The time of the mail: its Date header, else when the server received it. */
  static Instant instant(MimeMessage m) throws MessagingException {
    if (m.getSentDate() != null) {
      return m.getSentDate().toInstant();
    }
    return m.getReceivedDate() == null ? Instant.EPOCH : m.getReceivedDate().toInstant();
  }
}
