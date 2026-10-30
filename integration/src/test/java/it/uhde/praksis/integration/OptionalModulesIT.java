// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * A ruleset that offers more than this installation has the modules for: what asks for a module
 * that is not enabled is left out, and nothing else is affected; with the module it is there.
 */
class OptionalModulesIT {
  private static final String SEND =
      """
      id = "mail.send-message"
      effect = "external"
      needs = ["mail.send"]
      requires_modules = ["smtp"]

      [trigger]
      kind = "command"
      title = "Send a mail"
      [[trigger.param]]
      name = "to"
      type = "string"

      [[action]]
      id = "send"
      capability = "mail.send"
      args = { to = { cel = "event.data.to" }, subject = "s", text = "t" }
      """;

  private static final String VIEW =
      """
      title = "Reader"
      [[component]]
      type = "column"

      [[component.children]]
      type = "text"
      value = "Reading works"

      [[component.children]]
      type = "button"
      label = "Write"
      command = "mail.send-message"
      args = { to = "a@example.org" }
      requires_modules = ["smtp"]
      """;

  @Test
  void aRulesetWithAnOptionalPartWorksWithoutTheModuleAndGainsThePartWithIt() throws Exception {
    try (Harness h = new Harness()) {
      Files.writeString(h.home.resolve("ruleset/rules/mail.send-message.toml"), SEND);
      Files.createDirectories(h.home.resolve("ruleset/views"));
      Files.writeString(h.home.resolve("ruleset/views/reader.toml"), VIEW);
      String maildir = "[module.maildir]\nroot = '" + h.mail + "'\npoll_interval_ms = 20\n";
      Files.writeString(
          h.home.resolve("praksis.toml"),
          "format_version = 1\nenabled_modules = [\"maildir\"]\n" + maildir);

      // without the module: no error, no command, and the rest works
      Process daemon = h.startDaemon();
      // the rule that asks for the module is not checked and not a problem
      String verify = h.cli("verify").output();
      assertThat(verify).doesNotContain("unknown capability").doesNotContain("mail.send");
      assertThat(h.cli("commands").output()).doesNotContain("mail.send-message");
      assertThat(h.cli("view", "reader").output())
          .contains("Reading works")
          .doesNotContain("Write");
      assertThat(h.status().rules()).isEqualTo(1);
      h.stop(daemon);

      // with the module: the rule, the command and the button are there
      Files.writeString(
          h.home.resolve("praksis.toml"),
          "format_version = 1\nenabled_modules = [\"maildir\", \"smtp\"]\n"
              + maildir
              + """

              [module.smtp]
              host = "127.0.0.1"
              port = 2525
              security = "none"
              from = "me@example.org"
              """);
      daemon = h.startDaemon();
      assertThat(h.cli("commands").output()).contains("mail.send-message");
      assertThat(h.cli("view", "reader").output()).contains("Reading works").contains("Write");
      assertThat(h.status().rules()).isEqualTo(2);
      h.stop(daemon);
    }
  }
}
