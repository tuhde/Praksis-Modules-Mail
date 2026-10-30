// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Credentials;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.capability.EventSink;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.UIDFolder;
import jakarta.mail.search.FlagTerm;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.angus.mail.imap.IMAPFolder;

/**
 * Watches the folder of one account and turns its mails into {@code mail.received} events.
 *
 * <p>A mail is named by the UID validity of its folder and its UID, which the server never reuses
 * as long as the validity stays the same. The idempotency key contains both, so a mail that is
 * still in the folder after a restart is announced again and dropped by the store, and a folder
 * that the server renumbered (a new validity) is treated as new. Which mails are handled when the
 * daemon first sees the folder is the setting {@code initial}; afterwards every mail with a higher
 * UID than the last one announced is handled, whether it has been read or not.
 */
final class AccountWatch {
  private static final System.Logger LOG = System.getLogger(AccountWatch.class.getName());
  private static final int BATCH = 200;

  /**
   * Flags can change without a notice the poll can see, so the cache is synced this often. Less
   * than the time a watched folder is trusted ({@link HeaderCache#WATCHED_MAX_AGE_MILLIS}).
   */
  private static final long FULL_SYNC_MILLIS = 4 * 60 * 1000;

  /** How long the watcher waits before it connects again. */
  private static final long RECONNECT_MILLIS = 5000;

  private static final List<String> HEADERS =
      List.of(
          "From",
          "To",
          "Cc",
          "Subject",
          "Message-ID",
          "Date",
          "List-Id",
          "Precedence",
          "X-Spam-Flag");

  private final String account;
  private final ImapSettings settings;
  private Credentials credentials;
  private Connection connection;
  private ScheduledExecutorService poller;
  private long validity = -1;
  private long lastUid = -1;

  /** The header cache of the account, if the daemon gave the module a directory. */
  private HeaderCache cache;

  /** Set when the server said something changed: the next poll syncs the cache whatever it sees. */
  private final AtomicBoolean changed = new AtomicBoolean();

  private final AtomicBoolean pollQueued = new AtomicBoolean();
  private volatile boolean stopping;
  private volatile Connection idleConnection;
  private Thread watcher;
  private long syncedNext = -1;
  private int syncedTotal = -1;
  private long syncedAt;

  AccountWatch(String account, ImapSettings settings) {
    this.account = account;
    this.settings = settings;
  }

  synchronized void useCredentials(Credentials credentials) {
    this.credentials = credentials;
  }

  synchronized void useDataDirectory(java.nio.file.Path directory) {
    if (settings.cache() && cache == null) {
      try {
        cache = HeaderCache.open(directory, settings, account);
      } catch (java.sql.SQLException e) {
        LOG.log(System.Logger.Level.WARNING, "imap: no header cache: " + e.getMessage());
      }
    }
  }

  synchronized void start(EventSink sink) throws CapabilityException {
    if (poller != null) {
      throw new CapabilityException("imap source is already started", false);
    }
    connection = new Connection(settings, account);
    connection.useCredentials(credentials);
    poller =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "imap-poll-" + account);
              t.setDaemon(true);
              return t;
            });
    stopping = false;
    poller.scheduleWithFixedDelay(
        () -> poll(sink), 0, settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    watcher = new Thread(() -> watch(sink), "imap-idle-" + account);
    watcher.setDaemon(true);
    watcher.start();
  }

  synchronized void stop() {
    if (poller == null) {
      return;
    }
    stopping = true;
    Connection idle = idleConnection;
    if (idle != null) {
      idle.close(); // ends the IDLE that the watcher is waiting in
    }
    watcher.interrupt();
    poller.shutdownNow();
    try {
      poller.awaitTermination(5, TimeUnit.SECONDS);
      watcher.join(5000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    poller = null;
    watcher = null;
    connection.close();
    if (cache != null) {
      cache.release();
      cache = null;
    }
  }

  /**
   * Waits on the server with IDLE and wakes the poll when it says something changed, so that new
   * mail and changes are seen at once instead of at the next poll. The poll stays as the fallback:
   * a server without IDLE, a connection that broke or one that went silent only make it slower.
   */
  private void watch(EventSink sink) {
    while (!stopping) {
      Connection own = new Connection(settings, account);
      own.useCredentials(credentials);
      idleConnection = own;
      String shown = null;
      try {
        var store = own.store();
        if (!store.hasCapability("IDLE")) {
          LOG.log(
              System.Logger.Level.INFO,
              "imap " + settings.account() + ": the server has no IDLE, polling only");
          return;
        }
        IMAPFolder folder = (IMAPFolder) store.getFolder(settings.folder());
        folder.open(Folder.READ_ONLY);
        shown = ImapRead.display(folder);
        folder.addMessageCountListener(
            new jakarta.mail.event.MessageCountAdapter() {
              @Override
              public void messagesAdded(jakarta.mail.event.MessageCountEvent e) {
                wake(sink);
              }

              @Override
              public void messagesRemoved(jakarta.mail.event.MessageCountEvent e) {
                wake(sink);
              }
            });
        folder.addMessageChangedListener(e -> wake(sink));
        wake(sink); // what happened while there was no connection
        HeaderCache c = cache;
        if (c != null) {
          c.watching(shown, true);
        }
        while (!stopping) {
          folder.idle();
        }
      } catch (MessagingException | CapabilityException | RuntimeException e) {
        if (!stopping) {
          LOG.log(
              System.Logger.Level.WARNING,
              "imap " + settings.account() + ": waiting for news failed: " + e.getMessage());
        }
      } finally {
        HeaderCache c = cache;
        if (c != null && shown != null) {
          c.watching(shown, false);
        }
        own.close();
      }
      try {
        Thread.sleep(RECONNECT_MILLIS);
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  /** The server reported a change: poll now, once, however many reports there are. */
  private void wake(EventSink sink) {
    changed.set(true);
    ScheduledExecutorService executor = poller;
    if (executor != null && pollQueued.compareAndSet(false, true)) {
      try {
        executor.execute(
            () -> {
              pollQueued.set(false);
              poll(sink);
            });
      } catch (RejectedExecutionException e) {
        pollQueued.set(false); // stopping
      }
    }
  }

  private void poll(EventSink sink) {
    try {
      IMAPFolder folder = (IMAPFolder) connection.store().getFolder(settings.folder());
      folder.open(Folder.READ_ONLY);
      try {
        announceNew(folder, sink);
        syncCache(folder, sink);
      } finally {
        try {
          folder.close(false);
        } catch (MessagingException e) {
          connection.close();
        }
      }
    } catch (MessagingException | CapabilityException | RuntimeException e) {
      // tried again at the next poll, on a new connection
      LOG.log(System.Logger.Level.WARNING, "imap " + settings.account() + ": " + e.getMessage());
      connection.close();
    }
  }

  /**
   * Brings the cache of the folder up to date when something shows that it changed: the server said
   * so, the number of mails or the next UID moved, or it was not done for a while (flags change
   * without either). Announces what changed as {@code mail.changed}.
   */
  private void syncCache(IMAPFolder folder, EventSink sink) throws MessagingException {
    HeaderCache c = cache;
    if (c == null) {
      return;
    }
    long next = folder.getUIDNext();
    int total = folder.getMessageCount();
    long now = System.currentTimeMillis();
    boolean due =
        changed.getAndSet(false)
            || next != syncedNext
            || total != syncedTotal
            || now - syncedAt > FULL_SYNC_MILLIS;
    if (!due) {
      return;
    }
    String shown = ImapRead.display(folder);
    try {
      HeaderCache.Change change = c.sync(folder, shown);
      syncedNext = next;
      syncedTotal = total;
      syncedAt = now;
      if (change.any()) {
        sink.accept(changedEvent(shown, change));
      }
    } catch (java.sql.SQLException e) {
      LOG.log(System.Logger.Level.WARNING, "imap: header cache failed: " + e.getMessage());
      cache = null;
      c.release();
    }
  }

  private Event changedEvent(String folder, HeaderCache.Change change) {
    Map<String, Object> data = new HashMap<>();
    data.put("account", account);
    data.put("folder", folder);
    data.put("added", (long) change.added());
    data.put("removed", (long) change.removed());
    data.put("changed", (long) change.changed());
    return new Event(
        UUID.randomUUID().toString(),
        "mail.changed",
        "imap",
        Instant.now(),
        "mail:imap:" + settings.account() + ":" + folder,
        Optional.empty(),
        Optional.empty(),
        0,
        data);
  }

  private void announceNew(IMAPFolder folder, EventSink sink) throws MessagingException {
    long current = folder.getUIDValidity();
    if (current != validity) {
      if (validity >= 0) {
        LOG.log(
            System.Logger.Level.WARNING,
            "imap "
                + settings.account()
                + ": the UIDs of "
                + settings.folder()
                + " were renumbered");
      }
      validity = current;
      lastUid = -1;
    }
    long uidNext = folder.getUIDNext();
    Message[] candidates;
    if (lastUid < 0) {
      candidates =
          switch (settings.initial()) {
            case ALL -> folder.getMessages();
            case UNSEEN -> folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            case NONE -> new Message[0];
          };
    } else {
      candidates = folder.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID);
    }
    long announcedUpTo = lastUid < 0 ? 0 : lastUid;
    for (int from = 0; from < candidates.length; from += BATCH) {
      Message[] batch =
          Arrays.copyOfRange(candidates, from, Math.min(candidates.length, from + BATCH));
      FetchProfile profile = new FetchProfile();
      profile.add(UIDFolder.FetchProfileItem.UID);
      profile.add(FetchProfile.Item.SIZE);
      HEADERS.forEach(profile::add);
      folder.fetch(batch, profile);
      List<Message> ordered = new ArrayList<>(List.of(batch));
      ordered.sort(Comparator.comparingLong(m -> uidOf(folder, m)));
      for (Message message : ordered) {
        long uid = uidOf(folder, message);
        if (lastUid >= 0 && uid <= lastUid) {
          continue; // the server always includes the last mail of a range
        }
        sink.accept(event(folder, message, uid));
        announcedUpTo = Math.max(announcedUpTo, uid);
      }
    }
    lastUid = lastUid < 0 ? uidNext - 1 : Math.max(lastUid, announcedUpTo);
  }

  private static long uidOf(IMAPFolder folder, Message m) {
    try {
      return folder.getUID(m);
    } catch (MessagingException e) {
      throw new IllegalStateException(e);
    }
  }

  private Event event(IMAPFolder folder, Message message, long uid) throws MessagingException {
    String messageId = ImapSource.header(message, "Message-ID");
    String id = new MessageId(account, settings.folder(), validity, uid, messageId).encode();
    String unique = settings.account() + ":" + settings.folder() + ":" + validity + ":" + uid;
    Map<String, Object> data = new HashMap<>();
    data.put("message", id);
    data.put("account", account);
    data.put("folder", settings.folder());
    data.put("size", (long) Math.max(0, message.getSize()));
    data.put("from", ImapSource.header(message, "From"));
    data.put("to", ImapSource.header(message, "To"));
    data.put("cc", ImapSource.header(message, "Cc"));
    data.put("subject", ImapSource.header(message, "Subject"));
    data.put("message_id", messageId);
    data.put("date", ImapSource.header(message, "Date"));
    data.put("list_id", ImapSource.header(message, "List-Id"));
    data.put("precedence", ImapSource.header(message, "Precedence"));
    data.put("x_spam_flag", ImapSource.header(message, "X-Spam-Flag"));
    return new Event(
        UUID.randomUUID().toString(),
        "mail.received",
        "imap",
        Instant.now(),
        "mail:imap:" + unique,
        Optional.of("imap:" + unique),
        Optional.empty(),
        0,
        data);
  }
}
