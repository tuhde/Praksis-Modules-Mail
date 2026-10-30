// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.contacts;

import it.uhde.praksis.capability.CapabilityException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The address book of an account, in a SQLite file in the data directory of the module. It is
 * learned, not typed in: each address that is added counts how often it was seen and when, and a
 * search ranks by that. The capabilities of the module share one instance (see {@link #open}).
 * Addresses that are never written to by a person (no-reply, bounces, mailing daemons) are not
 * kept.
 */
final class Book {
  private static final Map<Path, Book> OPEN = new HashMap<>();
  private static final int SCHEMA = 1;

  /** The most contacts kept; when there are more the ones seen least and longest ago go. */
  static final int MAX_CONTACTS = 20_000;

  private static final Pattern NOT_A_PERSON =
      Pattern.compile(
          "(^|[._+-])(no-?reply|do-?not-?reply|mailer-daemon|postmaster|bounces?|noreply)([._+@-]|$)",
          Pattern.CASE_INSENSITIVE);

  private final Path file;
  private final Connection db;
  private int users;

  private Book(Path file, Connection db) {
    this.file = file;
    this.db = db;
  }

  static synchronized Book open(Path directory) throws CapabilityException {
    if (directory == null) {
      throw new CapabilityException(
          "the address book needs a data directory, which the daemon did not give", false);
    }
    Path file = directory.resolve("contacts.db");
    try {
      Book book = OPEN.get(file);
      if (book == null) {
        book = new Book(file, connect(file));
        OPEN.put(file, book);
      }
      book.users++;
      return book;
    } catch (SQLException e) {
      throw new CapabilityException("cannot open the address book: " + e.getMessage(), true, e);
    }
  }

  private static Connection connect(Path file) throws SQLException {
    Connection db = DriverManager.getConnection("jdbc:sqlite:" + file);
    try (Statement s = db.createStatement()) {
      s.execute("PRAGMA journal_mode = WAL");
      int version;
      try (ResultSet r = s.executeQuery("PRAGMA user_version")) {
        version = r.next() ? r.getInt(1) : 0;
      }
      if (version == 0) {
        s.execute(
            "CREATE TABLE IF NOT EXISTS contact (address TEXT PRIMARY KEY, name TEXT NOT NULL,"
                + " name_l TEXT NOT NULL, seen INTEGER NOT NULL, last INTEGER NOT NULL)"
                + " WITHOUT ROWID");
        s.execute("PRAGMA user_version = " + SCHEMA);
      } else if (version != SCHEMA) {
        db.close();
        throw new SQLException("the address book has the schema " + version + ", not " + SCHEMA);
      }
    }
    return db;
  }

  void release() {
    synchronized (Book.class) {
      if (--users > 0) {
        return;
      }
      OPEN.remove(file);
    }
    synchronized (this) {
      try {
        db.close();
      } catch (SQLException e) {
        // everything was committed when it was written
      }
    }
  }

  /** Whether an address belongs to a person, as far as its name can tell. */
  static boolean isPerson(String address) {
    return !NOT_A_PERSON.matcher(address).find();
  }

  /** The addresses of a header-style list, as the book would keep them. */
  static List<InternetAddress> parse(String header) {
    List<InternetAddress> out = new ArrayList<>();
    if (header == null || header.isBlank()) {
      return out;
    }
    try {
      for (InternetAddress a : InternetAddress.parseHeader(header, false)) {
        if (!a.isGroup() && a.getAddress() != null && a.getAddress().matches("[^@\\s]+@[^@\\s]+")) {
          out.add(a);
        }
      }
    } catch (AddressException e) {
      // what cannot be read gives no addresses
    }
    return out;
  }

  /** Counts the addresses of the list; returns how many were new. */
  synchronized int[] add(String header, long now) throws SQLException {
    int seen = 0;
    int added = 0;
    db.setAutoCommit(false);
    try {
      for (InternetAddress a : parse(header)) {
        String address = a.getAddress().toLowerCase(Locale.ROOT);
        if (!isPerson(address)) {
          continue;
        }
        seen++;
        String name = a.getPersonal() == null ? "" : a.getPersonal().strip();
        boolean known;
        try (PreparedStatement p = db.prepareStatement("SELECT 1 FROM contact WHERE address = ?")) {
          p.setString(1, address);
          try (ResultSet r = p.executeQuery()) {
            known = r.next();
          }
        }
        if (known) {
          try (PreparedStatement p =
              db.prepareStatement(
                  "UPDATE contact SET seen = seen + 1, last = ?,"
                      + " name = CASE WHEN ? <> '' THEN ? ELSE name END,"
                      + " name_l = CASE WHEN ? <> '' THEN ? ELSE name_l END WHERE address = ?")) {
            p.setLong(1, now);
            p.setString(2, name);
            p.setString(3, name);
            p.setString(4, name);
            p.setString(5, name.toLowerCase(Locale.ROOT));
            p.setString(6, address);
            p.executeUpdate();
          }
        } else {
          try (PreparedStatement p =
              db.prepareStatement(
                  "INSERT INTO contact (address, name, name_l, seen, last) VALUES (?, ?, ?, 1, ?)")) {
            p.setString(1, address);
            p.setString(2, name);
            p.setString(3, name.toLowerCase(Locale.ROOT));
            p.setLong(4, now);
            p.executeUpdate();
            added++;
          }
        }
      }
      prune();
      db.commit();
      return new int[] {seen, added};
    } catch (SQLException | RuntimeException e) {
      db.rollback();
      throw e;
    } finally {
      db.setAutoCommit(true);
    }
  }

  private void prune() throws SQLException {
    long total;
    try (Statement s = db.createStatement();
        ResultSet r = s.executeQuery("SELECT count(*) FROM contact")) {
      total = r.next() ? r.getLong(1) : 0;
    }
    if (total > MAX_CONTACTS) {
      try (PreparedStatement p =
          db.prepareStatement(
              "DELETE FROM contact WHERE address IN (SELECT address FROM contact"
                  + " ORDER BY seen ASC, last ASC LIMIT ?)")) {
        p.setLong(1, total - MAX_CONTACTS + MAX_CONTACTS / 10);
        p.executeUpdate();
      }
    }
  }

  /**
   * The contacts that match every word of the query at the start of a word of the name, of the
   * address, or of its domain; the ones seen most and last first. An empty query gives the most
   * used.
   */
  synchronized List<Map<String, Object>> search(String query, int limit) throws SQLException {
    List<String> words =
        java.util.Arrays.stream(query.toLowerCase(Locale.ROOT).strip().split("\\s+"))
            .filter(w -> !w.isEmpty())
            .toList();
    StringBuilder sql =
        new StringBuilder("SELECT address, name, seen, last FROM contact WHERE 1 = 1");
    for (int i = 0; i < words.size(); i++) {
      sql.append(
          " AND (instr(' ' || replace(replace(name_l, '.', ' '), '-', ' '), ' ' || ?) > 0"
              + " OR instr(address, ?) = 1 OR instr(address, '@' || ?) > 0"
              + " OR instr(address, '.' || ?) > 0)");
    }
    sql.append(" ORDER BY seen DESC, last DESC, address LIMIT ?");
    List<Map<String, Object>> out = new ArrayList<>();
    try (PreparedStatement p = db.prepareStatement(sql.toString())) {
      int n = 1;
      for (String w : words) {
        for (int k = 0; k < 4; k++) {
          p.setString(n++, w);
        }
      }
      p.setInt(n, limit);
      try (ResultSet r = p.executeQuery()) {
        while (r.next()) {
          Map<String, Object> item = new LinkedHashMap<>();
          String address = r.getString(1);
          String name = r.getString(2);
          item.put("address", address);
          item.put("name", name);
          item.put("display", name.isEmpty() ? address : quoted(name) + " <" + address + ">");
          item.put("seen", r.getLong(3));
          item.put("last", Instant.ofEpochMilli(r.getLong(4)));
          out.add(item);
        }
      }
    }
    return out;
  }

  /** A name as an address header writes it: in quotes if it has anything but plain words. */
  private static String quoted(String name) {
    return name.matches("[\\p{L}\\p{N} .'-]+")
        ? name
        : "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  /** How many contacts there are. */
  synchronized long count() throws SQLException {
    try (Statement s = db.createStatement();
        ResultSet r = s.executeQuery("SELECT count(*) FROM contact")) {
      return r.next() ? r.getLong(1) : 0;
    }
  }

  synchronized boolean remove(String address) throws SQLException {
    try (PreparedStatement p = db.prepareStatement("DELETE FROM contact WHERE address = ?")) {
      p.setString(1, address.strip().toLowerCase(Locale.ROOT));
      return p.executeUpdate() > 0;
    }
  }
}
