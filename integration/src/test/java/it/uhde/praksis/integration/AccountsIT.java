// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Several accounts with the runtime image: [module.maildir.work] and [module.maildir.private]. A
 * rule that moves mail moves each one inside its own account, because the path says where it is; a
 * view names the account it means; and a notification names the account unless it is the only one.
 */
class AccountsIT {
  private static final String VIEW =
      """
      title = "Accounts"
      [source.accounts]
      capability = "mail.accounts"
      [source.work]
      capability = "mail.list"
      args = { account = "work", folder = "INBOX" }
      [source.private]
      capability = "mail.list"
      args = { account = "private", folder = "Archive" }

      [[component]]
      type = "table"
      rows = { cel = "source.accounts.items" }
      columns = [ { title = "Account", value = { cel = "item.name" } }, { title = "State", value = { cel = "item.state" } } ]
      [[component]]
      type = "text"
      value = { cel = "'work: ' + string(source.work.count) + ', private archive: ' + string(source.private.count)" }
      """;

  private static final String NOTIFY =
      """
      id = "mail.notify"
      effect = "local-write"
      needs = ["notify.show"]
      [trigger]
      kind = "event"
      type = "mail.received"
      [[action]]
      id = "show"
      capability = "notify.show"
      args = { title = { cel = "event.data.from + ((has(event.data.account) && event.data.account != 'default') ? ' (' + event.data.account + ')' : '')" }, body = { cel = "event.data.subject" } }
      """;

  private static void accountsOf(Harness h, Path work, Path home, Path recorder) throws Exception {
    for (Path root : List.of(work, home)) {
      for (String d : new String[] {"new", "cur", "tmp"}) {
        Files.createDirectories(root.resolve(d));
      }
    }
    Files.writeString(
        h.home.resolve("praksis.toml"),
        """
        format_version = 1
        enabled_modules = ["maildir", "notify"]

        [module.maildir.work]
        root = '%s'
        poll_interval_ms = 20

        [module.maildir.private]
        root = '%s'
        poll_interval_ms = 20

        [module.notify]
        command = ['%s']
        """
            .formatted(work, home, recorder));
  }

  private static Path recorder(Harness h) throws Exception {
    Path script = h.home.resolve("fake-notify");
    Files.writeString(
        script,
        "#!/bin/sh\nfor a in \"$@\"; do printf '%s\\n' \"$a\" >> "
            + h.home.resolve("notified")
            + "; done\nprintf -- '--end--\\n' >> "
            + h.home.resolve("notified")
            + "\n");
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
    return script;
  }

  @Test
  void eachMailIsHandledInItsOwnAccountAndTheViewAndTheNotificationNameThem() throws Exception {
    try (Harness h = new Harness()) {
      Path work = h.home.resolve("work");
      Path home = h.home.resolve("private");
      accountsOf(h, work, home, recorder(h));
      Files.createDirectories(h.home.resolve("ruleset/views"));
      Files.writeString(h.home.resolve("ruleset/views/accounts.toml"), VIEW);
      Files.writeString(h.home.resolve("ruleset/rules/mail.notify.toml"), NOTIFY);
      Files.writeString(
          h.home.resolve("policy.toml"),
          Files.readString(h.home.resolve("policy.toml"))
              + """

              [[grant]]
              id = "accounts"
              layer = "ruleset"
              capability = "mail.accounts"
              [[grant]]
              id = "list"
              layer = "ruleset"
              capability = "mail.list"
              [[grant]]
              id = "notify"
              layer = "ruleset"
              capability = "notify.show"
              """);
      // a mail in each account's new directory; the rule archives it where it is
      Files.writeString(
          work.resolve("new/1.w"), "From: boss@example.org\nSubject: from work\n\nx\n");
      Files.writeString(
          home.resolve("new/1.h"), "From: mum@example.org\nSubject: from home\n\nx\n");
      Process daemon = h.startDaemon();

      Harness.await(
          "both mails to be archived in their own account",
          () ->
              Files.isDirectory(work.resolve(".Archive/cur"))
                  && Files.isDirectory(home.resolve(".Archive/cur"))
                  && Files.list(work.resolve(".Archive/cur")).count() == 1
                  && Files.list(home.resolve(".Archive/cur")).count() == 1);
      assertThat(Files.readString(Files.list(work.resolve(".Archive/cur")).findFirst().get()))
          .contains("from work");
      assertThat(Files.readString(Files.list(home.resolve(".Archive/cur")).findFirst().get()))
          .contains("from home");
      assertThat(h.log()).extracting(l -> l.outcome()).doesNotContain("FAILED");

      // the notifications name the account, as separate arguments
      Harness.await(
          "both notifications",
          () -> Files.readString(h.home.resolve("notified")).split("--end--").length >= 2);
      String notified = Files.readString(h.home.resolve("notified"));
      assertThat(notified)
          .contains("boss@example.org (work)")
          .contains("mum@example.org (private)")
          .contains("from work")
          .contains("from home");

      // the view names the accounts it means and the module lists them
      String shown = h.cli("view", "accounts").output();
      assertThat(shown).contains("work").contains("private").contains("ok");
      assertThat(shown).contains("work: 0, private archive: 1");
      h.stop(daemon);
    }
  }
}
