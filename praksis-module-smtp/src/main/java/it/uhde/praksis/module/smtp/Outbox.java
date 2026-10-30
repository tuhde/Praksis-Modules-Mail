// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.smtp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * What was sent, in a SQLite file in the data directory (in memory if there is none). It does two
 * things. It counts the mails of the last hour, which is the send limit: it survives a restart of
 * the daemon, so a faulty rule cannot get a new allowance by crashing the daemon. And it remembers
 * the key of every call: a call that is repeated with the same key (the engine does so after a
 * crash) is answered with the mail that was sent and sends nothing. The mail itself is kept for a
 * day for that, no longer. This is a record of what the module did; it can be thrown away only by
 * also losing the limit and the repeat protection.
 */
final class Outbox implements AutoCloseable {
  private static final long HOUR = 60L * 60 * 1000;
  private static final long KEEP = 24 * HOUR;
  private static final int SCHEMA = 1;

  /** A mail that was sent. */
  record Sent(String messageId, byte[] raw, int recipients) {}

  private final Connection db;

  private Outbox(Connection db) {
    this.db = db;
  }

  static Outbox open(Path directory, String account) throws SQLException {
    String url =
        directory == null
            ? "jdbc:sqlite::memory:"
            : "jdbc:sqlite:"
                + directory.resolve(account.replaceAll("[^A-Za-z0-9._@-]", "_") + ".outbox.db");
    if (directory != null) {
      try {
        Files.createDirectories(directory);
      } catch (java.io.IOException e) {
        throw new SQLException("cannot make " + directory + ": " + e.getMessage(), e);
      }
    }
    Connection db = DriverManager.getConnection(url);
    try (Statement s = db.createStatement()) {
      s.execute("PRAGMA journal_mode = WAL");
      s.execute("PRAGMA synchronous = FULL"); // a sent mail must not be forgotten
      int version;
      try (ResultSet r = s.executeQuery("PRAGMA user_version")) {
        version = r.next() ? r.getInt(1) : 0;
      }
      if (version == 0) {
        s.execute(
            "CREATE TABLE IF NOT EXISTS sent (key TEXT PRIMARY KEY, at INTEGER NOT NULL,"
                + " message_id TEXT NOT NULL, recipients INTEGER NOT NULL, raw BLOB NOT NULL)"
                + " WITHOUT ROWID");
        s.execute("CREATE INDEX IF NOT EXISTS sent_at ON sent (at)");
        s.execute("PRAGMA user_version = " + SCHEMA);
      } else if (version != SCHEMA) {
        db.close();
        throw new SQLException("the outbox has the schema " + version + ", not " + SCHEMA);
      }
    }
    return new Outbox(db);
  }

  /** The mail that this key sent, if it did. */
  synchronized Optional<Sent> byKey(String key) throws SQLException {
    try (PreparedStatement p =
        db.prepareStatement("SELECT message_id, raw, recipients FROM sent WHERE key = ?")) {
      p.setString(1, key);
      try (ResultSet r = p.executeQuery()) {
        return r.next()
            ? Optional.of(new Sent(r.getString(1), r.getBytes(2), r.getInt(3)))
            : Optional.empty();
      }
    }
  }

  /** How many mails were sent in the last hour. */
  synchronized int lastHour(long now) throws SQLException {
    try (PreparedStatement p = db.prepareStatement("SELECT count(*) FROM sent WHERE at > ?")) {
      p.setLong(1, now - HOUR);
      try (ResultSet r = p.executeQuery()) {
        return r.next() ? r.getInt(1) : 0;
      }
    }
  }

  /** When the oldest mail of the last hour leaves it, i.e. when one more may be sent. */
  synchronized long nextFree(long now) throws SQLException {
    try (PreparedStatement p = db.prepareStatement("SELECT min(at) FROM sent WHERE at > ?")) {
      p.setLong(1, now - HOUR);
      try (ResultSet r = p.executeQuery()) {
        return r.next() && r.getLong(1) > 0 ? r.getLong(1) + HOUR : now;
      }
    }
  }

  /** Records a sent mail under the key of the call, and forgets what is older than a day. */
  synchronized void record(String key, long now, Sent sent) throws SQLException {
    try (PreparedStatement p =
        db.prepareStatement(
            "INSERT OR REPLACE INTO sent (key, at, message_id, recipients, raw)"
                + " VALUES (?, ?, ?, ?, ?)")) {
      p.setString(1, key);
      p.setLong(2, now);
      p.setString(3, sent.messageId());
      p.setInt(4, sent.recipients());
      p.setBytes(5, sent.raw());
      p.executeUpdate();
    }
    try (PreparedStatement p = db.prepareStatement("DELETE FROM sent WHERE at < ?")) {
      p.setLong(1, now - KEEP);
      p.executeUpdate();
    }
  }

  @Override
  public synchronized void close() {
    try {
      db.close();
    } catch (SQLException e) {
      // nothing is lost: every record was committed when it was made
    }
  }
}
