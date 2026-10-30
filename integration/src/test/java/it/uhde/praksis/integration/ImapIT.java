// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.imap.ImapHostManager;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Flags;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * The IMAP module of the runtime image against an IMAP server in this process: the same rule as for
 * Maildir moves the mails, and a daemon killed with SIGKILL neither loses nor repeats anything.
 */
class ImapIT {
  private static class Server implements AutoCloseable {
    final GreenMail greenMail =
        new GreenMail(new ServerSetup(0, "127.0.0.1", "imap").dynamicPort());
    final GreenMailUser user;

    Server() {
      greenMail.start();
      user = greenMail.setUser("me@example.org", "me", "secret");
    }

    void deliver(String subject, String... more) throws Exception {
      String headers =
          "Subject: " + subject + "\r\nMessage-ID: <" + subject.replace(' ', '.') + "@t>\r\n";
      String text = headers + String.join("\r\n", more) + "\r\n\r\nbody\r\n";
      MimeMessage message =
          new MimeMessage(
              GreenMailUtil.getSession(greenMail.getImap().getServerSetup()),
              new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
      message.setFlag(Flags.Flag.SEEN, false);
      user.deliver(message);
    }

    int count(String folder) throws Exception {
      ImapHostManager manager = greenMail.getManagers().getImapHostManager();
      var f = manager.getFolder(user, folder);
      return f == null ? 0 : f.getMessageCount();
    }

    @Override
    public void close() {
      greenMail.stop();
    }
  }

  private static void useImap(Harness h, Server server) throws Exception {
    useImap(h, server, false, "");
  }

  /**
   * Configures the module. With a binding the password comes from {@code [credential."imap:me"]} in
   * policy.toml, which names the parts that may ask for it; otherwise from a command in the module
   * settings.
   */
  private static void useImap(Harness h, Server server, boolean bound, String askers)
      throws Exception {
    Files.writeString(
        h.home.resolve("praksis.toml"),
        """
        format_version = 1
        enabled_modules = ["imap"]

        [module.imap]
        host = "127.0.0.1"
        port = %d
        security = "none"
        user = "me"
        %s
        poll_interval_ms = 50
        """
            .formatted(
                server.greenMail.getImap().getPort(),
                bound
                    ? "credential = \"imap:me\""
                    : "password_command = [\"sh\", \"-c\", \"printf secret\"]"));
    if (bound) {
      Files.writeString(
          h.home.resolve("policy.toml"),
          Files.readString(h.home.resolve("policy.toml"))
              + """

              [credential."imap:me"]
              command = ["sh", "-c", "printf secret"]
              capabilities = [%s]
              note = "the test mailbox"
              """
                  .formatted(askers));
    }
  }

  @Test
  void theRuleMovesMailOnAnImapServer() throws Exception {
    try (Server server = new Server();
        Harness h = new Harness()) {
      useImap(h, server, true, "\"imap.inbox\", \"mail.move\"");
      for (int i = 0; i < 5; i++) {
        server.deliver("mail " + i);
      }
      Process daemon = h.startDaemon();
      Harness.await("all mails to be archived", () -> server.count("Archive") == 5);
      assertThat(server.count("INBOX")).isZero();
      assertThat(h.status().modules()).containsExactly("imap");
      assertThat(h.log()).extracting(l -> l.outcome()).doesNotContain("FAILED");

      // the password came through the binding: asked for by name, audited, never shown
      assertThat(h.cli("grants").output())
          .contains("imap:me  for imap.inbox, mail.move, from the command 'sh'")
          .contains("(the test mailbox)")
          .doesNotContain("printf"); // the program is shown, not its arguments
      String audit = h.cli("audit", "-n", "100").output();
      assertThat(audit)
          .containsPattern("CREDENTIAL +- +allowed +imap:me \\{\"component\":\"imap\\.inbox\"\\}")
          .containsPattern("CREDENTIAL +- +allowed +imap:me \\{\"component\":\"mail\\.move\"\\}")
          .doesNotContain("secret");

      // mail that arrives later is handled too
      server.deliver("later");
      Harness.await("the later mail to be archived", () -> server.count("Archive") == 6);
      // the module keeps the headers of the account in a directory of its own in the home
      assertThat(h.home.resolve("data/imap/me@127.0.0.1.cache.db")).isRegularFile();
      java.nio.file.Path status =
          java.nio.file.Path.of("/proc", String.valueOf(daemon.pid()), "status");
      if (Files.exists(status)) {
        for (String line : Files.readAllLines(status)) {
          if (line.startsWith("VmRSS:")) {
            System.out.println(
                "footprint (IMAP daemon after 6 mails): " + line.replaceAll("\\s+", " "));
          }
        }
      }
      h.stop(daemon);
    }
  }

  @Test
  void aBindingThatDoesNotNameTheMoveCannotMoveAnything() throws Exception {
    try (Server server = new Server();
        Harness h = new Harness()) {
      useImap(h, server, true, "\"imap.inbox\"");
      server.deliver("stays");
      Process daemon = h.startDaemon();
      Harness.await(
          "the refusal to be logged",
          () ->
              h.log().stream()
                  .anyMatch(
                      l ->
                          l.message().contains("credential 'imap:me' is not bound for mail.move")));
      assertThat(server.count("INBOX")).isEqualTo(1);
      assertThat(server.count("Archive")).isZero();
      assertThat(h.cli("audit", "-n", "100").output())
          .containsPattern(
              "CREDENTIAL +- +DENIED +imap:me .*\\[credential 'imap:me' is not bound for mail\\.move\\]");
      h.stop(daemon);
    }
  }

  @Test
  void aDaemonKilledInTheMiddleOfABurstLosesNothingAndRepeatsNothing() throws Exception {
    int mails = 120;
    try (Server server = new Server();
        Harness h = new Harness()) {
      useImap(h, server);
      for (int i = 0; i < mails; i++) {
        server.deliver("burst " + i);
      }
      Process daemon = h.startDaemon();
      for (int round = 0; round < 2; round++) {
        int already = round;
        Harness.await("work to begin", () -> server.count("Archive") > already * 10);
        h.kill(daemon);
        assertThat(server.count("INBOX") + server.count("Archive")).isEqualTo(mails);
        if (round == 0) {
          assertThat(server.count("INBOX")).as("mails left when killed").isPositive();
        }
        daemon = h.startDaemon();
      }
      try {
        Harness.await("every mail to be archived", () -> server.count("Archive") == mails);
      } catch (AssertionError e) {
        throw new AssertionError(
            e.getMessage()
                + "\nINBOX "
                + server.count("INBOX")
                + ", Archive "
                + server.count("Archive")
                + ", status "
                + h.status()
                + "\nfailed: "
                + h.log().stream().filter(l -> "FAILED".equals(l.outcome())).limit(5).toList()
                + "\n"
                + h.daemonLog(),
            e);
      }
      Harness.await("the queue to be empty", () -> h.status().pending() == 0);
      Thread.sleep(300);

      assertThat(server.count("INBOX")).isZero();
      assertThat(server.count("Archive")).isEqualTo(mails);
      // events repeated after a crash found their mail already moved and still succeeded
      assertThat(h.log()).extracting(l -> l.outcome()).doesNotContain("FAILED");
      h.stop(daemon);
    }
  }

  @Test
  void aDaemonKilledWhileReplayingMissedMailFinishesTheJobWithoutRepeatingAnything()
      throws Exception {
    int mails = 60;
    try (Server server = new Server();
        Harness h = new Harness()) {
      h.grantMoves(java.util.Map.of()); // nothing is allowed yet
      useImap(h, server, true, "\"imap.inbox\", \"mail.move\"");
      for (int i = 0; i < mails; i++) {
        server.deliver("replay " + i);
      }
      Process daemon = h.startDaemon();
      Harness.await(
          "every mail to be missed",
          () -> h.cli("approvals").output().contains("mail.archive: " + mails));
      assertThat(server.count("INBOX")).isEqualTo(mails);

      // the grant is made, the replay starts, and the daemon dies in the middle of it
      Harness.CliResult allowed = h.cli("approvals", "allow", "1", "--always", "--replay");
      assertThat(allowed.exit()).as(allowed.output()).isZero();
      Harness.await("the replay to begin", () -> server.count("Archive") > 5);
      h.kill(daemon);
      assertThat(server.count("INBOX") + server.count("Archive")).isEqualTo(mails);

      // after the restart the grant is still there and the rest of the mail is still missed
      daemon = h.startDaemon();
      String after = h.cli("approvals").output();
      assertThat(after).contains("ALLOWED_ALWAYS").doesNotContain("Disabled rules");
      Harness.CliResult replay = h.cli("approvals", "replay", "mail.archive");
      assertThat(replay.exit()).as(replay.output()).isZero();
      try {
        Harness.await("every mail to be archived", () -> server.count("Archive") == mails);
      } catch (AssertionError e) {
        throw new AssertionError(
            e.getMessage()
                + "\nINBOX "
                + server.count("INBOX")
                + ", Archive "
                + server.count("Archive")
                + "\n"
                + h.cli("approvals").output()
                + "\nlog tail: "
                + h.log().stream().skip(Math.max(0, h.log().size() - 8)).toList()
                + "\n"
                + h.daemonLog(),
            e);
      }
      Harness.await(
          "nothing to be missed any more", () -> !h.cli("approvals").output().contains("Missed"));

      assertThat(server.count("INBOX")).isZero();
      // a mail that was moved just before the kill is moved again and found already there
      assertThat(h.log()).extracting(l -> l.outcome()).doesNotContain("FAILED");
      h.stop(daemon);
    }
  }

  @Test
  void aMultipartMailCanBeReadInTheImage() throws Exception {
    try (Server server = new Server();
        Harness h = new Harness()) {
      useImap(h, server, true, "\"imap.inbox\", \"mail.list\", \"mail.get\"");
      Files.createDirectories(h.home.resolve("ruleset/views"));
      Files.writeString(
          h.home.resolve("ruleset/views/reader.toml"),
          """
          title = "Reader"
          [source.list]
          capability = "mail.list"
          args = { folder = "INBOX" }
          # a source may use the one before it
          [source.mail]
          capability = "mail.get"
          args = { message = { cel = "source.list.items[0].id" } }

          [[component]]
          type = "text"
          value = { cel = "source.mail.subject + ': ' + source.mail.text" }

          [[component]]
          type = "table"
          rows = { cel = "source.mail.attachments" }
          columns = [ { title = "Attachment", value = { cel = "item.name" } } ]
          """);
      Files.writeString(
          h.home.resolve("policy.toml"),
          Files.readString(h.home.resolve("policy.toml"))
              + """

              [[grant]]
              id = "list"
              layer = "ruleset"
              capability = "mail.list"
              [[grant]]
              id = "get"
              layer = "ruleset"
              capability = "mail.get"
              """);
      // the mail goes to a folder that no rule empties: the rule of the harness moves the INBOX
      server.deliver(
          "multipart",
          "MIME-Version: 1.0",
          "Content-Type: multipart/mixed; boundary=\"b\"",
          "",
          "--b",
          "Content-Type: multipart/alternative; boundary=\"a\"",
          "",
          "--a",
          "Content-Type: text/plain",
          "",
          "the plain text",
          "--a",
          "Content-Type: text/html",
          "",
          "<p>the html</p>",
          "--a--",
          "--b",
          "Content-Type: application/pdf; name=\"report.pdf\"",
          "Content-Disposition: attachment; filename=\"report.pdf\"",
          "Content-Transfer-Encoding: base64",
          "",
          "AAECAw==",
          "--b--");
      Files.delete(h.home.resolve("ruleset/rules/mail.archive.toml"));
      Process daemon = h.startDaemon();

      String shown = h.cli("view", "reader").output();

      assertThat(shown).contains("multipart: ").contains("the plain text").contains("report.pdf");
      h.stop(daemon);
    }
  }
}
