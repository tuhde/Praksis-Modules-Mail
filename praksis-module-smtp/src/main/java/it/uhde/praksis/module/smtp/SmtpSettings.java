// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.smtp;

import it.uhde.praksis.capability.CapabilityException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code [module.smtp]} settings.
 *
 * <pre>
 * host             = "smtp.example.org"                    required
 * from             = "Me &lt;me@example.org&gt;"                required: the sender of the mails
 * allowed_from     = ["alias@example.org"]                 other addresses a call may send as
 * user             = "me@example.org"                      the login; without it there is no login
 * credential       = "smtp:work"                           the credential binding in policy.toml, or
 * password_command = ["pass", "show", "mail/example"]      a command whose output is the password;
 *                                                          exactly one of them if there is a user
 * security         = "ssl"      # ssl (default, port 465), starttls (587) or none (loopback only)
 * port             = 465
 * max_per_hour     = 30         # mails that may be sent per hour, over all callers
 * max_recipients   = 50         # recipients of one mail
 * max_bytes        = 26214400   # size of one mail
 * timeout_ms       = 60000
 * ca_file          = "/path/ca.pem"
 * </pre>
 *
 * @param host the server
 * @param port the port
 * @param security how the connection is protected
 * @param user the login name, or empty for no login
 * @param passwordCommand the command whose output is the password; empty if a credential is used
 * @param credential the credential binding that gives the password, or empty
 * @param from the sender address
 * @param allowedFrom the addresses (without names) that a call may use instead of {@code from}
 * @param maxPerHour how many mails may be sent in any hour
 * @param maxRecipients how many recipients one mail may have
 * @param maxBytes the size of a mail
 * @param timeout the longest the server may stay silent
 * @param caFile a PEM file with certificates to trust in addition to the system's
 */
record SmtpSettings(
    String host,
    int port,
    Security security,
    Optional<String> user,
    List<String> passwordCommand,
    Optional<String> credential,
    InternetAddress from,
    Set<String> allowedFrom,
    int maxPerHour,
    int maxRecipients,
    long maxBytes,
    Duration timeout,
    Optional<Path> caFile) {
  enum Security {
    SSL,
    STARTTLS,
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
          "from",
          "allowed_from",
          "max_per_hour",
          "max_recipients",
          "max_bytes",
          "timeout_ms",
          "ca_file");

  static SmtpSettings parse(Map<String, Object> settings) throws CapabilityException {
    for (String key : settings.keySet()) {
      if (!KEYS.contains(key)) {
        throw new CapabilityException("unknown smtp setting '" + key + "'", false);
      }
    }
    String host = string(settings, "host");
    Security security = Security.SSL;
    if (settings.get("security") instanceof String s) {
      try {
        security = Security.valueOf(s.toUpperCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new CapabilityException(
            "smtp setting 'security' must be one of [ssl, starttls, none]", false);
      }
    } else if (settings.containsKey("security")) {
      throw new CapabilityException("smtp setting 'security' must be a string", false);
    }
    if (security == Security.NONE && !loopback(host)) {
      throw new CapabilityException(
          "smtp setting 'security = \"none\"' is only allowed for localhost, "
              + "because the password and the mail would cross the network unprotected",
          false);
    }
    long port = number(settings, "port", security == Security.SSL ? 465 : 587, 1, 65535);
    Optional<String> user =
        settings.containsKey("user") ? Optional.of(string(settings, "user")) : Optional.empty();
    List<String> command = List.of();
    Optional<String> credential = Optional.empty();
    boolean hasCredential = settings.containsKey("credential");
    boolean hasCommand = settings.containsKey("password_command");
    if (user.isPresent() && hasCredential == hasCommand) {
      throw new CapabilityException(
          "smtp needs, with a 'user', exactly one of the settings 'credential' (the name of a"
              + " binding in policy.toml) and 'password_command' (a command that prints the"
              + " password)",
          false);
    }
    if (user.isEmpty() && (hasCredential || hasCommand)) {
      throw new CapabilityException(
          "smtp setting 'user' is required when there is a password", false);
    }
    if (hasCredential) {
      credential = Optional.of(string(settings, "credential"));
    } else if (hasCommand) {
      if (!(settings.get("password_command") instanceof List<?> list)
          || list.isEmpty()
          || !list.stream().allMatch(e -> e instanceof String s && !s.isBlank())) {
        throw new CapabilityException(
            "smtp setting 'password_command' must be a list with the command and its"
                + " arguments, e.g. [\"pass\", \"show\", \"mail/example\"]",
            false);
      }
      command = list.stream().map(String.class::cast).toList();
    }
    InternetAddress from = address(string(settings, "from"), "from");
    Set<String> allowed = new java.util.LinkedHashSet<>();
    if (settings.containsKey("allowed_from")) {
      if (!(settings.get("allowed_from") instanceof List<?> list)) {
        throw new CapabilityException(
            "smtp setting 'allowed_from' must be a list of addresses", false);
      }
      for (Object o : list) {
        allowed.add(address(String.valueOf(o), "allowed_from").getAddress().toLowerCase());
      }
    }
    Optional<Path> ca = Optional.empty();
    if (settings.containsKey("ca_file")) {
      Path file = Path.of(string(settings, "ca_file"));
      if (!Files.isRegularFile(file)) {
        throw new CapabilityException("smtp setting 'ca_file': no such file " + file, false);
      }
      ca = Optional.of(file);
    }
    return new SmtpSettings(
        host,
        (int) port,
        security,
        user,
        command,
        credential,
        from,
        Set.copyOf(allowed),
        (int) number(settings, "max_per_hour", 30, 1, 100_000),
        (int) number(settings, "max_recipients", 50, 1, 1000),
        number(settings, "max_bytes", 25L * 1024 * 1024, 1024, 100L * 1024 * 1024),
        Duration.ofMillis(number(settings, "timeout_ms", 60_000, 1, Long.MAX_VALUE)),
        ca);
  }

  /** The account, for the outbox file and for messages. */
  String account() {
    return user.orElse("anonymous") + "@" + host;
  }

  /** Whether a call may send as this address. */
  boolean mayUse(InternetAddress sender) {
    String a = sender.getAddress().toLowerCase();
    return a.equals(from.getAddress().toLowerCase()) || allowedFrom.contains(a);
  }

  /** One address as a header writes it, checked; no line break, no list. */
  static InternetAddress address(String text, String what) throws CapabilityException {
    if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\0') >= 0) {
      throw new CapabilityException(what + " must not contain a line break", false);
    }
    try {
      InternetAddress[] parsed = InternetAddress.parse(text, true);
      if (parsed.length != 1 || parsed[0].isGroup()) {
        throw new CapabilityException(what + " must be exactly one address: " + text, false);
      }
      parsed[0].validate();
      if (!parsed[0].getAddress().contains("@")) {
        throw new CapabilityException(what + " needs a domain: " + text, false);
      }
      return parsed[0];
    } catch (AddressException e) {
      throw new CapabilityException(what + " is not an address: " + text, false);
    }
  }

  private static boolean loopback(String host) {
    return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
  }

  private static String string(Map<String, Object> settings, String key)
      throws CapabilityException {
    if (!(settings.get(key) instanceof String s) || s.isBlank()) {
      throw new CapabilityException("smtp setting '" + key + "' is required (a string)", false);
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
          "smtp setting '" + key + "' must be an integer from " + min + " to " + max, false);
    }
    return n;
  }
}
