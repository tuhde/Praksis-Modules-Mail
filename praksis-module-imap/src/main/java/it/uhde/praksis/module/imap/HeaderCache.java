// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import it.uhde.praksis.mail.html.Threads;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.MimeMessage;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.angus.mail.imap.IMAPFolder;

/**
 * The headers of the mails of one account, kept in a SQLite file in the data directory of the
 * module, so that a list, a count or a search by sender or subject needs no round trip to the
 * server. It is a cache: everything in it can be fetched again, and a file that cannot be read or
 * has another schema is thrown away.
 *
 * <p>A folder is brought up to date by {@link #sync}: one fetch of the UIDs and flags of the whole
 * folder finds the mails that were removed and the flags that changed, and only the headers of
 * mails with a UID that is not cached yet are fetched. A folder whose UID validity changed is
 * emptied first, because the server then numbers its mails anew. A folder is trusted for the {@code
 * cache_max_age_ms} after it was synced, or for as long as a connection that is waiting for news
 * (IDLE) watches it.
 *
 * <p>All capabilities and the source of an account share one instance (see {@link #open}); the
 * methods are synchronized, and a sync holds the lock while it talks to the server.
 */
final class HeaderCache {
  private static final int SCHEMA = 2;
  private static final int BATCH = 200;

  /**
   * How long a folder that a connection watches is trusted without a sync. A server tells an idle
   * connection about new and removed mail, but it only should tell it about changed flags, so the
   * trust must end; the poll of the source syncs the folder before that.
   */
  static final long WATCHED_MAX_AGE_MILLIS = 5 * 60 * 1000;

  private static final Map<Path, HeaderCache> OPEN = new HashMap<>();

  /** What a sync changed in a folder. */
  record Change(int added, int removed, int changed) {
    boolean any() {
      return added + removed + changed > 0;
    }
  }

  /** The numbers a folder list shows. */
  record Counts(long total, long unread) {}

  private record Row(
      long uid,
      String messageId,
      String from,
      String subject,
      String date,
      long time,
      long size,
      boolean seen,
      boolean flagged,
      String inReplyTo,
      String references) {}

  private final Path file;
  private final Duration maxAge;
  private final Connection db;
  private int users;
  private long watchedMaxAge = WATCHED_MAX_AGE_MILLIS;

  /** When each folder was last synced, and the counts that a STATUS gave for folders not synced. */
  private final Map<String, Long> syncedAt = new HashMap<>();

  private final Map<String, Counts> status = new HashMap<>();
  private final Map<String, Long> statusAt = new HashMap<>();

  /** Folders that a connection waits on, so that nothing can have changed without a notice. */
  private final Set<String> watched = new HashSet<>();

  /** The hierarchy separator of the server, to name a folder as mail ids do. */
  private char separator = '/';

  private final String account;

  private HeaderCache(Path file, Duration maxAge, Connection db, String account) {
    this.account = account;
    this.file = file;
    this.maxAge = maxAge;
    this.db = db;
  }

  /**
   * The cache of the account in this directory. Everyone who opens it must {@link #release} it.
   *
   * @throws SQLException if the file cannot be used even after it was thrown away
   */
  static synchronized HeaderCache open(Path directory, ImapSettings settings, String account)
      throws SQLException {
    // two accounts may have the same login at the same server, so the name is part of the file;
    // the account without a name keeps the name its cache always had
    String login = settings.account().replaceAll("[^A-Za-z0-9._@-]", "_");
    Path file =
        directory.resolve(
            (Accounts.DEFAULT.equals(account) ? "" : account + ".") + login + ".cache.db");
    HeaderCache cache = OPEN.get(file);
    if (cache == null) {
      cache = new HeaderCache(file, settings.cacheMaxAge(), connect(file), account);
      cache.loadSeparator();
      OPEN.put(file, cache);
    }
    cache.users++;
    return cache;
  }

  private static Connection connect(Path file) throws SQLException {
    for (int attempt = 0; ; attempt++) {
      Connection db = null;
      try {
        db = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement s = db.createStatement()) {
          s.execute("PRAGMA journal_mode = WAL");
          s.execute("PRAGMA synchronous = NORMAL");
          int version;
          try (ResultSet r = s.executeQuery("PRAGMA user_version")) {
            version = r.next() ? r.getInt(1) : 0;
          }
          if (version != SCHEMA) {
            s.execute("DROP TABLE IF EXISTS message");
            s.execute("DROP TABLE IF EXISTS folder");
            s.execute("DROP TABLE IF EXISTS meta");
            s.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID");
            s.execute(
                "CREATE TABLE folder (name TEXT PRIMARY KEY, validity INTEGER NOT NULL)"
                    + " WITHOUT ROWID");
            s.execute(
                "CREATE TABLE message (folder TEXT NOT NULL, uid INTEGER NOT NULL,"
                    + " message_id TEXT NOT NULL, sender TEXT NOT NULL, sender_l TEXT NOT NULL,"
                    + " subject TEXT NOT NULL, subject_l TEXT NOT NULL, date TEXT NOT NULL,"
                    + " time INTEGER NOT NULL, size INTEGER NOT NULL, seen INTEGER NOT NULL,"
                    + " flagged INTEGER NOT NULL, in_reply_to TEXT NOT NULL, refs TEXT NOT NULL,"
                    + " PRIMARY KEY (folder, uid)) WITHOUT ROWID");
            s.execute("PRAGMA user_version = " + SCHEMA);
          }
        }
        return db;
      } catch (SQLException e) {
        if (db != null) {
          try {
            db.close();
          } catch (SQLException ignored) {
            // the file is thrown away below
          }
        }
        // a file that cannot be used is only a lost cache: start again once
        if (attempt > 0) {
          throw e;
        }
        for (String suffix : new String[] {"", "-wal", "-shm"}) {
          try {
            java.nio.file.Files.deleteIfExists(Path.of(file + suffix));
          } catch (java.io.IOException io) {
            throw e;
          }
        }
      }
    }
  }

  private void loadSeparator() {
    try (PreparedStatement p =
            db.prepareStatement("SELECT value FROM meta WHERE key = 'separator'");
        ResultSet r = p.executeQuery()) {
      if (r.next() && r.getString(1).length() == 1) {
        separator = r.getString(1).charAt(0);
      }
    } catch (SQLException e) {
      // the default is right for most servers, and the next sync learns it
    }
  }

  /** The folder as a rule and a mail id write it: levels separated by {@code /}. */
  synchronized String shown(String serverName) {
    return separator == '/' || separator == 0 ? serverName : serverName.replace(separator, '/');
  }

  /** Learns how the server separates the levels of folder names. */
  synchronized void separator(char separator) {
    if (separator == this.separator || separator == 0) {
      return;
    }
    this.separator = separator;
    try {
      update(
          "INSERT OR REPLACE INTO meta (key, value) VALUES ('separator', ?)",
          String.valueOf(separator));
    } catch (SQLException e) {
      // only the memory of it is lost
    }
  }

  /** Gives the cache back; the file is closed when the last user has done so. */
  void release() {
    synchronized (HeaderCache.class) {
      if (--users > 0) {
        return;
      }
      OPEN.remove(file);
    }
    synchronized (this) {
      try {
        db.close();
      } catch (SQLException e) {
        // nothing is lost: this is only a cache
      }
    }
  }

  // ------------------------------------------------------------------------------- freshness

  /** Whether the folder can be answered from the cache without asking the server. */
  synchronized boolean fresh(String folder) {
    Long at = syncedAt.get(folder);
    if (at == null) {
      return false;
    }
    long age = System.currentTimeMillis() - at;
    return age <= (watched.contains(folder) ? watchedMaxAge : maxAge.toMillis());
  }

  /** For tests: how long a watched folder is trusted. */
  synchronized void watchedMaxAge(long millis) {
    watchedMaxAge = millis;
  }

  /** A connection waits on the folder (or has stopped doing so). */
  synchronized void watching(String folder, boolean on) {
    if (on) {
      watched.add(folder);
    } else {
      watched.remove(folder);
    }
  }

  /** Forgets that the folder is up to date: the next read syncs it. */
  synchronized void stale(String folder) {
    syncedAt.remove(folder);
    status.remove(folder);
    statusAt.remove(folder);
  }

  // ----------------------------------------------------------------------------------- sync

  /**
   * Brings the cached state of an opened folder in line with the server.
   *
   * @param folder the folder, open
   * @param name the name the cache and the mail ids use for it
   */
  synchronized Change sync(IMAPFolder folder, String name) throws MessagingException, SQLException {
    separator(folder.getSeparator());
    long validity = folder.getUIDValidity();
    boolean autoCommit = db.getAutoCommit();
    db.setAutoCommit(false);
    try {
      int removed = 0;
      int changed = 0;
      Long known = validity(name);
      if (known == null || known != validity) {
        if (known != null) {
          removed = (int) one("SELECT count(*) FROM message WHERE folder = ?", name);
          update("DELETE FROM message WHERE folder = ?", name);
        }
        update("INSERT OR REPLACE INTO folder (name, validity) VALUES (?, ?)", name, validity);
      }
      Message[] all = folder.getMessages();
      FetchProfile light = new FetchProfile();
      light.add(UIDFolder.FetchProfileItem.UID);
      light.add(FetchProfile.Item.FLAGS);
      folder.fetch(all, light);
      Map<Long, Message> onServer = new LinkedHashMap<>();
      for (Message m : all) {
        onServer.put(folder.getUID(m), m);
      }

      Map<Long, Integer> cached = new HashMap<>();
      try (PreparedStatement p =
          db.prepareStatement("SELECT uid, seen, flagged FROM message WHERE folder = ?")) {
        p.setString(1, name);
        try (ResultSet r = p.executeQuery()) {
          while (r.next()) {
            cached.put(r.getLong(1), r.getInt(2) | (r.getInt(3) << 1));
          }
        }
      }
      try (PreparedStatement gone =
              db.prepareStatement("DELETE FROM message WHERE folder = ? AND uid = ?");
          PreparedStatement flags =
              db.prepareStatement(
                  "UPDATE message SET seen = ?, flagged = ? WHERE folder = ? AND uid = ?")) {
        for (var entry : cached.entrySet()) {
          Message m = onServer.get(entry.getKey());
          if (m == null) {
            gone.setString(1, name);
            gone.setLong(2, entry.getKey());
            gone.addBatch();
            removed++;
            continue;
          }
          int now = bits(m.getFlags());
          if (now != entry.getValue()) {
            flags.setInt(1, now & 1);
            flags.setInt(2, (now >> 1) & 1);
            flags.setString(3, name);
            flags.setLong(4, entry.getKey());
            flags.addBatch();
            changed++;
          }
        }
        gone.executeBatch();
        flags.executeBatch();
      }

      List<Message> added = new ArrayList<>();
      onServer.forEach(
          (uid, m) -> {
            if (!cached.containsKey(uid)) {
              added.add(m);
            }
          });
      for (int from = 0; from < added.size(); from += BATCH) {
        Message[] batch =
            added.subList(from, Math.min(added.size(), from + BATCH)).toArray(new Message[0]);
        FetchProfile full = new FetchProfile();
        full.add(FetchProfile.Item.ENVELOPE);
        full.add(FetchProfile.Item.SIZE);
        full.add(IMAPFolder.FetchProfileItem.HEADERS);
        folder.fetch(batch, full);
        insert(folder, name, batch);
      }
      db.commit();
      syncedAt.put(name, System.currentTimeMillis());
      status.remove(name);
      statusAt.remove(name);
      return new Change(added.size(), removed, changed);
    } catch (MessagingException | SQLException | RuntimeException e) {
      db.rollback();
      stale(name);
      throw e;
    } finally {
      db.setAutoCommit(autoCommit);
    }
  }

  private void insert(IMAPFolder folder, String name, Message[] batch)
      throws MessagingException, SQLException {
    try (PreparedStatement p =
        db.prepareStatement(
            "INSERT OR REPLACE INTO message (folder, uid, message_id, sender, sender_l, subject,"
                + " subject_l, date, time, size, seen, flagged, in_reply_to, refs)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      for (Message message : batch) {
        MimeMessage m = (MimeMessage) message;
        String[] ids = m.getHeader("Message-ID");
        String[] dates = m.getHeader("Date");
        String from = ImapRead.address(m.getFrom());
        String subject = ImapRead.subject(m);
        int flags = bits(m.getFlags());
        p.setString(1, name);
        p.setLong(2, folder.getUID(m));
        p.setString(3, ids == null || ids.length == 0 ? "" : ids[0]);
        p.setString(4, from);
        p.setString(5, from.toLowerCase(Locale.ROOT));
        p.setString(6, subject);
        p.setString(7, subject.toLowerCase(Locale.ROOT));
        p.setString(8, dates == null || dates.length == 0 ? "" : dates[0]);
        p.setLong(9, ImapRead.instant(m).toEpochMilli());
        p.setLong(10, Math.max(0, m.getSize()));
        p.setInt(11, flags & 1);
        p.setInt(12, (flags >> 1) & 1);
        p.setString(13, ImapSource.header(m, "In-Reply-To"));
        p.setString(14, ImapSource.header(m, "References"));
        p.addBatch();
      }
      p.executeBatch();
    }
  }

  private static int bits(Flags flags) {
    return (flags.contains(Flags.Flag.SEEN) ? 1 : 0) | (flags.contains(Flags.Flag.FLAGGED) ? 2 : 0);
  }

  // ----------------------------------------------------------------------------------- reads

  /** The UID validity of the cached folder, or null if it was never synced. */
  synchronized Long validity(String folder) throws SQLException {
    try (PreparedStatement p = db.prepareStatement("SELECT validity FROM folder WHERE name = ?")) {
      p.setString(1, folder);
      try (ResultSet r = p.executeQuery()) {
        return r.next() ? r.getLong(1) : null;
      }
    }
  }

  /** How many mails of the cached folder there are. */
  synchronized long total(String folder) throws SQLException {
    return one("SELECT count(*) FROM message WHERE folder = ?", folder);
  }

  /** The numbers of the folder if they are known and recent, else empty. */
  synchronized Optional<Counts> counts(String folder) throws SQLException {
    if (fresh(folder)) {
      try (PreparedStatement p =
          db.prepareStatement(
              "SELECT count(*), coalesce(sum(1 - seen), 0) FROM message WHERE folder = ?")) {
        p.setString(1, folder);
        try (ResultSet r = p.executeQuery()) {
          r.next();
          return Optional.of(new Counts(r.getLong(1), r.getLong(2)));
        }
      }
    }
    Long at = statusAt.get(folder);
    if (at != null && System.currentTimeMillis() - at <= maxAge.toMillis()) {
      return Optional.ofNullable(status.get(folder));
    }
    return Optional.empty();
  }

  /** The numbers that the server gave for a folder that is not synced. */
  synchronized void remember(String folder, Counts counts) {
    status.put(folder, counts);
    statusAt.put(folder, System.currentTimeMillis());
  }

  /** The entries of {@code mail.list} for a window of the folder. */
  synchronized List<Map<String, Object>> list(
      String folder, String sort, boolean reverse, int offset, int limit, boolean threaded)
      throws SQLException {
    boolean time = sort.equals("time");
    String column =
        switch (sort) {
          case "from" -> "sender_l";
          case "subject" -> "subject_l";
          case "size" -> "size";
          default -> "uid";
        };
    String direction = time != reverse ? "DESC" : "ASC";
    return shaped(
        query(
            "SELECT * FROM message WHERE folder = ? ORDER BY "
                + column
                + " "
                + direction
                + ", uid "
                + direction
                + " LIMIT ? OFFSET ?",
            folder,
            folder,
            (long) limit,
            (long) offset),
        threaded);
  }

  /** The newest mails whose sender or subject (or either, for {@code all}) contains the text. */
  synchronized List<Map<String, Object>> search(String folder, String text, String in, int limit)
      throws SQLException {
    String q = text.toLowerCase(Locale.ROOT);
    String where =
        switch (in) {
          case "from" -> "instr(sender_l, ?) > 0";
          case "subject" -> "instr(subject_l, ?) > 0";
          default -> "(instr(sender_l, ?) > 0 OR instr(subject_l, ?) > 0)";
        };
    boolean both = !in.equals("from") && !in.equals("subject");
    Object[] args =
        both ? new Object[] {folder, q, q, (long) limit} : new Object[] {folder, q, (long) limit};
    return shaped(
        query(
            "SELECT * FROM message WHERE folder = ? AND " + where + " ORDER BY uid DESC LIMIT ?",
            folder,
            args),
        false);
  }

  /** How many mails of the folder the search matches, however many are returned. */
  synchronized long searchCount(String folder, String text, String in) throws SQLException {
    String q = text.toLowerCase(Locale.ROOT);
    return switch (in) {
      case "from" ->
          one(
              "SELECT count(*) FROM message WHERE folder = ? AND instr(sender_l, ?) > 0",
              folder,
              q);
      case "subject" ->
          one(
              "SELECT count(*) FROM message WHERE folder = ? AND instr(subject_l, ?) > 0",
              folder,
              q);
      default ->
          one(
              "SELECT count(*) FROM message WHERE folder = ? AND"
                  + " (instr(sender_l, ?) > 0 OR instr(subject_l, ?) > 0)",
              folder,
              q,
              q);
    };
  }

  private List<Map<String, Object>> query(String sql, String folder, Object... args)
      throws SQLException {
    Long validity = validity(folder);
    List<Map<String, Object>> out = new ArrayList<>();
    try (PreparedStatement p = db.prepareStatement(sql)) {
      bind(p, args);
      try (ResultSet r = p.executeQuery()) {
        while (r.next()) {
          Row row =
              new Row(
                  r.getLong("uid"),
                  r.getString("message_id"),
                  r.getString("sender"),
                  r.getString("subject"),
                  r.getString("date"),
                  r.getLong("time"),
                  r.getLong("size"),
                  r.getInt("seen") == 1,
                  r.getInt("flagged") == 1,
                  r.getString("in_reply_to"),
                  r.getString("refs"));
          out.add(item(account, folder, validity == null ? 0 : validity, row));
        }
      }
    }
    return out;
  }

  /** The page as a plain list, or put into conversations (within the page). */
  private static List<Map<String, Object>> shaped(
      List<Map<String, Object>> page, boolean threaded) {
    if (threaded) {
      return Threads.arrange(page);
    }
    page.forEach(
        m -> {
          m.remove("message_id");
          m.remove("in_reply_to");
          m.remove("references");
        });
    return page;
  }

  private static Map<String, Object> item(String account, String folder, long validity, Row row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", new MessageId(account, folder, validity, row.uid(), row.messageId()).encode());
    item.put("from", row.from());
    item.put("subject", row.subject());
    item.put("date", row.date());
    item.put("time", Instant.ofEpochMilli(row.time()));
    item.put("size", row.size());
    item.put("unread", !row.seen());
    item.put("starred", row.flagged());
    // only for conversations; list() takes them out again if they are not asked for
    item.put("message_id", row.messageId());
    item.put("in_reply_to", row.inReplyTo());
    item.put("references", row.references());
    return item;
  }

  // ---------------------------------------------------------------------------- local changes

  /** The server accepted new flags for a mail: the cache has them too, without a sync. */
  synchronized void flags(String folder, long uid, Boolean seen, Boolean flagged) {
    try {
      if (seen != null) {
        update(
            "UPDATE message SET seen = ? WHERE folder = ? AND uid = ?", seen ? 1 : 0, folder, uid);
      }
      if (flagged != null) {
        update(
            "UPDATE message SET flagged = ? WHERE folder = ? AND uid = ?",
            flagged ? 1 : 0,
            folder,
            uid);
      }
    } catch (SQLException e) {
      stale(folder);
    }
  }

  /** The mail is gone from the folder (deleted or moved). */
  synchronized void remove(String folder, long uid) {
    try {
      update("DELETE FROM message WHERE folder = ? AND uid = ?", folder, uid);
    } catch (SQLException e) {
      stale(folder);
    }
  }

  // ------------------------------------------------------------------------------------ jdbc

  private long one(String sql, Object... args) throws SQLException {
    try (PreparedStatement p = db.prepareStatement(sql)) {
      bind(p, args);
      try (ResultSet r = p.executeQuery()) {
        return r.next() ? r.getLong(1) : 0;
      }
    }
  }

  private void update(String sql, Object... args) throws SQLException {
    try (PreparedStatement p = db.prepareStatement(sql)) {
      bind(p, args);
      p.executeUpdate();
    }
  }

  private static void bind(PreparedStatement p, Object[] args) throws SQLException {
    for (int i = 0; i < args.length; i++) {
      p.setObject(i + 1, args[i]);
    }
  }

  @Override
  public String toString() {
    return "HeaderCache" + Arrays.asList(file);
  }
}
