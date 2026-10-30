// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import it.uhde.praksis.capability.CapabilityException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code [module.imap]} settings.
 *
 * <pre>
 * host             = "imap.example.org"                    required
 * user             = "me@example.org"                      required
 * credential       = "imap:work"                           the credential binding in policy.toml, or
 * password_command = ["pass", "show", "mail/example"]      a command whose output is the password;
 *                                                          exactly one of them
 * security         = "ssl"      # ssl (default, port 993), starttls (143) or none (loopback only)
 * port             = 993
 * folder           = "INBOX"    # the folder to watch
 * initial          = "unseen"   # unseen (default), all or none: what to handle at the first start
 * poll_interval_ms = 30000
 * timeout_ms       = 60000
 * ca_file          = "/path/ca.pem"   # an extra certificate authority to trust
 * cache            = true       # keep the headers of the folders in a local database (default)
 * cache_max_age_ms = 60000      # how old a folder in the cache may be before a read asks the server
 * </pre>
 *
 * @param host the server
 * @param port the port
 * @param security how the connection is protected
 * @param user the login name
 * @param passwordCommand the command whose output is the password; empty if a credential is used
 * @param credential the name of the credential binding that gives the password, or empty
 * @param folder the folder the source watches
 * @param initial which mails of the folder are handled when the daemon first sees it
 * @param pollInterval how often the source looks for new mail
 * @param timeout the longest the server may stay silent
 * @param caFile a PEM file with certificates to trust in addition to the system's
 * @param cache whether the headers are kept in a local database; it is used only if the daemon
 *     gives the module a directory
 * @param cacheMaxAge how old the cached state of a folder that no connection watches may be
 */
record ImapSettings(
    String host,
    int port,
    Security security,
    String user,
    List<String> passwordCommand,
    Optional<String> credential,
    String folder,
    Initial initial,
    Duration pollInterval,
    Duration timeout,
    Optional<Path> caFile,
    boolean cache,
    Duration cacheMaxAge) {
  enum Security {
    SSL,
    STARTTLS,
    NONE
  }

  enum Initial {
    /** The mails that are not marked as seen. */
    UNSEEN,
    /** Every mail in the folder. */
    ALL,
    /** Only mails that arrive later. */
    NONE
  }

  private static final Set<String> KEYS =
      Set.of(
          "host",
          "port",
          "security",
          "user",
          "password_command",
          "credential",
          "folder",
          "initial",
          "poll_interval_ms",
          "timeout_ms",
          "ca_file",
          "cache",
          "cache_max_age_ms");

  static ImapSettings parse(Map<String, Object> settings) throws CapabilityException {
    for (String key : settings.keySet()) {
      if (!KEYS.contains(key)) {
        throw new CapabilityException("unknown imap setting '" + key + "'", false);
      }
    }
    String host = string(settings, "host", null);
    String user = string(settings, "user", null);
    Security security = Security.valueOf(choice(settings, "security", "ssl", Security.values()));
    if (security == Security.NONE && !loopback(host)) {
      throw new CapabilityException(
          "imap setting 'security = \"none\"' is only allowed for localhost, "
              + "because the password and the mail would cross the network unprotected",
          false);
    }
    long port = number(settings, "port", security == Security.SSL ? 993 : 143, 1, 65535);
    List<String> command = List.of();
    Optional<String> credential = Optional.empty();
    if (settings.containsKey("credential") == settings.containsKey("password_command")) {
      throw new CapabilityException(
          "imap needs exactly one of the settings 'credential' (the name of a binding in"
              + " policy.toml) and 'password_command' (a command that prints the password)",
          false);
    }
    if (settings.containsKey("credential")) {
      credential = Optional.of(string(settings, "credential", null));
    } else {
      if (!(settings.get("password_command") instanceof List<?> list)
          || list.isEmpty()
          || !list.stream().allMatch(e -> e instanceof String s && !s.isBlank())) {
        throw new CapabilityException(
            "imap setting 'password_command' must be a list with the command and its"
                + " arguments, e.g. [\"pass\", \"show\", \"mail/example\"]",
            false);
      }
      command = list.stream().map(String.class::cast).toList();
    }
    Optional<Path> ca = Optional.empty();
    if (settings.containsKey("ca_file")) {
      Path file = Path.of(string(settings, "ca_file", null));
      if (!Files.isRegularFile(file)) {
        throw new CapabilityException("imap setting 'ca_file': no such file " + file, false);
      }
      ca = Optional.of(file);
    }
    return new ImapSettings(
        host,
        (int) port,
        security,
        user,
        command,
        credential,
        string(settings, "folder", "INBOX"),
        Initial.valueOf(choice(settings, "initial", "unseen", Initial.values())),
        Duration.ofMillis(number(settings, "poll_interval_ms", 30_000, 1, Long.MAX_VALUE)),
        Duration.ofMillis(number(settings, "timeout_ms", 60_000, 1, Long.MAX_VALUE)),
        ca,
        flag(settings, "cache", true),
        Duration.ofMillis(number(settings, "cache_max_age_ms", 60_000, 0, Long.MAX_VALUE)));
  }

  private static boolean flag(Map<String, Object> settings, String key, boolean fallback)
      throws CapabilityException {
    Object v = settings.get(key);
    if (v == null) {
      return fallback;
    }
    if (!(v instanceof Boolean b)) {
      throw new CapabilityException("imap setting '" + key + "' must be true or false", false);
    }
    return b;
  }

  /** The login on this server, for keys and messages. */
  String account() {
    return user + "@" + host;
  }

  private static boolean loopback(String host) {
    return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
  }

  private static String string(Map<String, Object> settings, String key, String fallback)
      throws CapabilityException {
    Object v = settings.get(key);
    if (v == null && fallback != null) {
      return fallback;
    }
    if (!(v instanceof String s) || s.isBlank()) {
      throw new CapabilityException("imap setting '" + key + "' is required (a string)", false);
    }
    return s;
  }

  private static long number(
      Map<String, Object> settings, String key, long fallback, long min, long max)
      throws CapabilityException {
    Object v = settings.get(key);
    if (v == null) {
      return fallback;
    }
    if (!(v instanceof Long n) || n < min || n > max) {
      throw new CapabilityException(
          "imap setting '" + key + "' must be an integer >= " + min, false);
    }
    return n;
  }

  private static <E extends Enum<E>> String choice(
      Map<String, Object> settings, String key, String fallback, E[] values)
      throws CapabilityException {
    String v = string(settings, key, fallback).toLowerCase(Locale.ROOT);
    for (E e : values) {
      if (e.name().toLowerCase(Locale.ROOT).equals(v)) {
        return e.name();
      }
    }
    throw new CapabilityException(
        "imap setting '"
            + key
            + "' must be one of "
            + java.util.Arrays.stream(values).map(e -> e.name().toLowerCase(Locale.ROOT)).toList(),
        false);
  }
}
