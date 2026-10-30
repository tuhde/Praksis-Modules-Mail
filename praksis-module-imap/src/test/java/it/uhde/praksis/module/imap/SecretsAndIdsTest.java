// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.CapabilityException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecretsAndIdsTest {
  @Test
  void theOutputOfTheCommandIsThePasswordWithoutItsFinalLineBreak() throws Exception {
    assertThat(Secrets.read(List.of("sh", "-c", "printf 'pass word\\n'"))).isEqualTo("pass word");
    assertThat(Secrets.read(List.of("sh", "-c", "printf 'a\\r\\n'"))).isEqualTo("a");
    assertThat(Secrets.read(List.of("sh", "-c", "printf '  x \\n\\n'"))).isEqualTo("  x \n");
  }

  @Test
  void failuresNameTheCommandButNeverShowItsOutput() {
    assertThatThrownBy(() -> Secrets.read(List.of("sh", "-c", "echo topsecret; exit 3")))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining("password_command 'sh' failed with exit code 3")
        .hasMessageNotContaining("topsecret");
    assertThatThrownBy(() -> Secrets.read(List.of("sh", "-c", "true")))
        .hasMessageContaining("printed nothing");
    assertThatThrownBy(() -> Secrets.read(List.of("no-such-command-here")))
        .hasMessageContaining("cannot run password_command 'no-such-command-here'");
  }

  @Test
  void messageIdsSurviveTheRoundTripWhateverTheyContain() {
    MessageId id = new MessageId("Arbeit/Ü: 2024", 1234567890123L, 42, "<a:b@c.example>");
    assertThat(MessageId.parse(id.encode())).contains(id);
    MessageId none = new MessageId("INBOX", 1, 2, "");
    assertThat(MessageId.parse(none.encode())).contains(none);
    assertThat(id.encode()).startsWith("imap:").doesNotContain(" ");
  }

  @Test
  void whatIsNotAnIdIsRejected() {
    for (String bad :
        new String[] {
          null, "", "/tmp/mail", "imap:", "imap:a:b:c", "imap:!!:1:2:", "imap:SU5CT1g:x:2:"
        }) {
      assertThat(MessageId.parse(bad)).as(String.valueOf(bad)).isEmpty();
    }
  }
}
