// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.smtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.CapabilityException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SmtpSettingsTest {
  private static Map<String, Object> base(Object... extra) {
    Map<String, Object> m = new HashMap<>();
    m.put("host", "smtp.example.org");
    m.put("from", "Me <me@example.org>");
    m.put("user", "me");
    m.put("credential", "smtp:work");
    for (int i = 0; i < extra.length; i += 2) {
      if (extra[i + 1] == null) {
        m.remove((String) extra[i]);
      } else {
        m.put((String) extra[i], extra[i + 1]);
      }
    }
    return m;
  }

  @Test
  void theDefaultsAreSafe() throws Exception {
    SmtpSettings s = SmtpSettings.parse(base());
    assertThat(s.security()).isEqualTo(SmtpSettings.Security.SSL);
    assertThat(s.port()).isEqualTo(465);
    assertThat(s.maxPerHour()).isEqualTo(30);
    assertThat(s.maxRecipients()).isEqualTo(50);
    assertThat(SmtpSettings.parse(base("security", "starttls")).port()).isEqualTo(587);
    assertThat(s.account()).isEqualTo("me@smtp.example.org");
  }

  @Test
  void whatIsWrongIsSaid() {
    assertThatThrownBy(() -> SmtpSettings.parse(base("nope", 1L)))
        .hasMessageContaining("unknown smtp setting 'nope'");
    assertThatThrownBy(() -> SmtpSettings.parse(base("host", null)))
        .hasMessageContaining("'host' is required");
    assertThatThrownBy(() -> SmtpSettings.parse(base("from", "not an address")))
        .isInstanceOf(CapabilityException.class);
    assertThatThrownBy(() -> SmtpSettings.parse(base("security", "none")))
        .hasMessageContaining("only allowed for localhost");
    assertThatThrownBy(() -> SmtpSettings.parse(base("credential", null)))
        .hasMessageContaining("exactly one of");
    assertThatThrownBy(
            () -> SmtpSettings.parse(base("password_command", List.of("pass", "show", "x"))))
        .hasMessageContaining("exactly one of");
    assertThatThrownBy(() -> SmtpSettings.parse(base("user", null)))
        .hasMessageContaining("'user' is required when there is a password");
    assertThatThrownBy(() -> SmtpSettings.parse(base("max_per_hour", 0L)))
        .hasMessageContaining("max_per_hour");
    assertThatThrownBy(() -> SmtpSettings.parse(base("allowed_from", "x@y.z")))
        .hasMessageContaining("list of addresses");
  }

  @Test
  void aServerWithoutLoginNeedsNoPassword() throws Exception {
    SmtpSettings s =
        SmtpSettings.parse(
            base("user", null, "credential", null, "security", "none", "host", "localhost"));
    assertThat(s.user()).isEmpty();
    assertThat(s.account()).isEqualTo("anonymous@localhost");
  }
}
