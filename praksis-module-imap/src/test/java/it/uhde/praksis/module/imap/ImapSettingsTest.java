// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.CapabilityException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImapSettingsTest {
  @TempDir Path dir;

  private static Map<String, Object> base() {
    Map<String, Object> m = new HashMap<>();
    m.put("host", "imap.example.org");
    m.put("user", "me");
    m.put("password_command", List.of("pass", "show", "mail"));
    return m;
  }

  private static Map<String, Object> with(Object... extra) {
    Map<String, Object> m = base();
    for (int i = 0; i < extra.length; i += 2) {
      m.put((String) extra[i], extra[i + 1]);
    }
    return m;
  }

  @Test
  void defaultsAreSecureAndSensible() throws Exception {
    ImapSettings s = ImapSettings.parse(base());
    assertThat(s.security()).isEqualTo(ImapSettings.Security.SSL);
    assertThat(s.port()).isEqualTo(993);
    assertThat(s.folder()).isEqualTo("INBOX");
    assertThat(s.initial()).isEqualTo(ImapSettings.Initial.UNSEEN);
    assertThat(s.pollInterval()).isEqualTo(Duration.ofSeconds(30));
    assertThat(s.timeout()).isEqualTo(Duration.ofSeconds(60));
    assertThat(s.passwordCommand()).containsExactly("pass", "show", "mail");
    assertThat(s.account()).isEqualTo("me@imap.example.org");
    assertThat(ImapSettings.parse(with("security", "starttls")).port()).isEqualTo(143);
  }

  @Test
  void everythingCanBeSet() throws Exception {
    Path ca = Files.writeString(dir.resolve("ca.pem"), "x");
    ImapSettings s =
        ImapSettings.parse(
            with(
                "port",
                1993L,
                "folder",
                "Work",
                "initial",
                "ALL",
                "poll_interval_ms",
                500L,
                "timeout_ms",
                1000L,
                "ca_file",
                ca.toString()));
    assertThat(s.port()).isEqualTo(1993);
    assertThat(s.folder()).isEqualTo("Work");
    assertThat(s.initial()).isEqualTo(ImapSettings.Initial.ALL);
    assertThat(s.pollInterval()).isEqualTo(Duration.ofMillis(500));
    assertThat(s.caFile()).contains(ca);
  }

  @Test
  void mistakesAreReportedClearly() {
    assertBad(Map.of(), "'host' is required");
    assertBad(with("password_command", "pass"), "'password_command' must be a list");
    assertBad(with("password_command", List.of()), "'password_command' must be a list");
    assertBad(with("credential", "imap:work"), "exactly one of the settings 'credential'");
    assertBad(with("nope", 1), "unknown imap setting 'nope'");
    assertBad(with("security", "tls13"), "must be one of [ssl, starttls, none]");
    assertBad(with("port", 0L), "'port' must be an integer");
    assertBad(with("port", "993"), "'port' must be an integer");
    assertBad(with("initial", "some"), "must be one of [unseen, all, none]");
    assertBad(with("ca_file", dir.resolve("missing.pem").toString()), "no such file");
  }

  @Test
  void theLoginComesFromACredentialOrACommandButNotBoth() throws Exception {
    Map<String, Object> m = base();
    m.remove("password_command");
    assertBad(m, "exactly one of the settings 'credential'");
    m.put("credential", "imap:work");
    ImapSettings s = ImapSettings.parse(m);
    assertThat(s.credential()).contains("imap:work");
    assertThat(s.passwordCommand()).isEmpty();
    assertThat(ImapSettings.parse(base()).credential()).isEmpty();
  }

  @Test
  void anUnprotectedConnectionIsOnlyForLocalhost() throws Exception {
    assertBad(with("security", "none"), "only allowed for localhost");
    assertThat(ImapSettings.parse(with("security", "none", "host", "127.0.0.1")).security())
        .isEqualTo(ImapSettings.Security.NONE);
    assertThat(ImapSettings.parse(with("security", "none", "host", "localhost")).port())
        .isEqualTo(143);
  }

  private static void assertBad(Map<String, Object> settings, String message) {
    assertThatThrownBy(() -> ImapSettings.parse(settings))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining(message);
  }
}
