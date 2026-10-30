// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Sending mail with the runtime image: the SMTP module in its own layer, the password from a
 * credential binding, the grant that an external effect always needs, the copy in the Sent folder,
 * and the limit per hour.
 */
class SmtpIT {
  private static final String COMMAND =
      """
      id = "mail.send-message"
      effect = "external"
      needs = ["mail.send", "mail.append"]

      [trigger]
      kind = "command"
      title = "Send a mail"
      [[trigger.param]]
      name = "to"
      type = "string"
      [[trigger.param]]
      name = "subject"
      type = "string"
      [[trigger.param]]
      name = "text"
      type = "string"
      [[trigger.param]]
      name = "attachments"
      type = "list"
      default = []
      widget = "files"

      [[action]]
      id = "send"
      capability = "mail.send"
      args = { to = { cel = "event.data.to" }, subject = { cel = "event.data.subject" }, text = { cel = "event.data.text" }, attachments = { cel = "event.data.attachments" } }

      [[action]]
      id = "keep"
      capability = "mail.append"
      args = { folder = "Sent", content = { cel = "results.send.raw" }, seen = true }
      """;

  private static GreenMail server() {
    GreenMail greenMail = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp").dynamicPort());
    greenMail.start();
    greenMail.setUser("me@example.org", "me", "secret");
    greenMail.setUser("anna@example.org", "anna", "x");
    return greenMail;
  }

  private static void configure(Harness h, GreenMail server, String extra, boolean grants)
      throws Exception {
    Files.writeString(
        h.home.resolve("praksis.toml"),
        """
        format_version = 1
        enabled_modules = ["maildir", "smtp"]

        [module.maildir]
        root = '%s'

        [module.smtp]
        host = "127.0.0.1"
        port = %d
        security = "none"
        user = "me"
        credential = "smtp:me"
        from = "Me <me@example.org>"
        %s
        """
            .formatted(h.mail, server.getSmtp().getPort(), extra));
    Files.writeString(h.home.resolve("ruleset/rules/mail.send-message.toml"), COMMAND);
    Files.writeString(
        h.home.resolve("policy.toml"),
        """
        format_version = 1

        [credential."smtp:me"]
        command = ["sh", "-c", "printf secret"]
        capabilities = ["mail.send"]
        note = "the test account"
        """
            + (grants
                ? """

                [[grant]]
                id = "send"
                layer = "ruleset"
                capability = "mail.send"

                [[grant]]
                id = "keep-sent"
                layer = "ruleset"
                capability = "mail.append"
                [grant.args]
                folder = { equals = "Sent" }
                """
                : ""));
  }

  private static String messageId(String raw) {
    return raw.lines()
        .filter(l -> l.toLowerCase().startsWith("message-id:"))
        .findFirst()
        .orElseThrow()
        .substring("message-id:".length())
        .strip();
  }

  @Test
  void aMailIsSentThroughTheGrantAndTheBindingAndACopyIsKept() throws Exception {
    GreenMail smtp = server();
    try (Harness h = new Harness()) {
      configure(h, smtp, "", true);
      Process daemon = h.startDaemon();

      Harness.CliResult done =
          h.cli(
              "run", "mail.send-message", "to=anna@example.org", "subject=Hello Anna", "text=Hi!");
      assertThat(done.exit()).as(done.output()).isZero();
      assertThat(done.output()).contains("OK");

      assertThat(smtp.getReceivedMessages()).hasSize(1);
      var got = smtp.getReceivedMessages()[0];
      assertThat(got.getSubject()).isEqualTo("Hello Anna");
      assertThat(got.getContent().toString()).contains("Hi!");

      // the copy is in the Sent folder of the Maildir and is the mail that went out
      assertThat(h.names(".Sent/cur")).hasSize(1);
      Path copy = h.mail.resolve(".Sent/cur").resolve(h.names(".Sent/cur").get(0));
      assertThat(messageId(Files.readString(copy))).isEqualTo(got.getHeader("Message-ID")[0]);

      // the password came through the binding, which names only the capability allowed to ask
      String audit = h.cli("audit", "-n", "100").output();
      assertThat(audit)
          .containsPattern("CREDENTIAL +- +allowed +smtp:me \\{\"component\":\"mail\\.send\"\\}")
          .doesNotContain("secret");
      assertThat(h.cli("commands").output()).contains("mail.send-message").contains("external");
      h.stop(daemon);
    } finally {
      smtp.stop();
    }
  }

  @Test
  void withoutAGrantNothingLeavesTheMachine() throws Exception {
    GreenMail smtp = server();
    try (Harness h = new Harness()) {
      configure(h, smtp, "", false);
      Process daemon = h.startDaemon();

      Harness.CliResult denied =
          h.cli("run", "mail.send-message", "to=anna@example.org", "subject=No", "text=No");

      assertThat(denied.exit()).as(denied.output()).isEqualTo(1);
      assertThat(denied.output()).contains("DENIED");
      assertThat(smtp.getReceivedMessages()).isEmpty();
      assertThat(h.names(".Sent/cur")).isEmpty();
      assertThat(h.cli("approvals").output()).contains("mail.send");
      h.stop(daemon);
    } finally {
      smtp.stop();
    }
  }

  @Test
  void theLimitPerHourStopsTheMailsAfterItAndSurvivesARestart() throws Exception {
    GreenMail smtp = server();
    try (Harness h = new Harness()) {
      configure(h, smtp, "max_per_hour = 1", true);
      Process daemon = h.startDaemon();

      assertThat(
              h.cli("run", "mail.send-message", "to=anna@example.org", "subject=One", "text=1")
                  .exit())
          .isZero();
      Harness.CliResult second =
          h.cli("run", "mail.send-message", "to=anna@example.org", "subject=Two", "text=2");
      assertThat(second.exit()).as(second.output()).isEqualTo(1);
      assertThat(second.output()).contains("limit of 1 mails per hour");
      assertThat(smtp.getReceivedMessages()).hasSize(1);

      // killing the daemon does not give a new allowance
      h.kill(daemon);
      daemon = h.startDaemon();
      Harness.CliResult third =
          h.cli("run", "mail.send-message", "to=anna@example.org", "subject=Three", "text=3");
      assertThat(third.output()).contains("limit of 1 mails per hour");
      assertThat(smtp.getReceivedMessages()).hasSize(1);
      h.stop(daemon);
    } finally {
      smtp.stop();
    }
  }

  @Test
  void aFileThePersonChoseIsAttachedByItsTokenAndNoPathCanBeSmuggledIn() throws Exception {
    GreenMail smtp = server();
    try (Harness h = new Harness()) {
      configure(h, smtp, "", true);
      Process daemon = h.startDaemon();
      Path secret = Files.writeString(h.home.resolve("secret.txt"), "top secret");

      try (it.uhde.praksis.protocol.Client client =
          it.uhde.praksis.protocol.Client.connect(h.home.resolve("praksis.sock"))) {
        // the frontend asks for a place, copies the chosen file there and keeps the token
        var ticket =
            (it.uhde.praksis.protocol.Response.Uploaded)
                client.send(new it.uhde.praksis.protocol.Request.Upload());
        Files.writeString(Path.of(ticket.path()), "the notes");
        assertThat(Path.of(ticket.path()).getParent()).isEqualTo(h.home.resolve("uploads"));

        var started =
            (it.uhde.praksis.protocol.Response.CommandStarted)
                client.send(
                    new it.uhde.praksis.protocol.Request.RunCommand(
                        "mail.send-message",
                        java.util.Map.of(
                            "to",
                            "anna@example.org",
                            "subject",
                            "With a file",
                            "text",
                            "see attached",
                            "attachments",
                            java.util.List.of(
                                java.util.Map.of("name", "notes.txt", "upload", ticket.token()))),
                        "test"));
        try {
          Harness.await("the mail to be sent", () -> smtp.getReceivedMessages().length == 1);
        } catch (AssertionError e) {
          throw new AssertionError(e.getMessage() + "\n" + h.log(), e);
        }
        jakarta.mail.Multipart parts =
            (jakarta.mail.Multipart) smtp.getReceivedMessages()[0].getContent();
        assertThat(parts.getBodyPart(1).getFileName()).isEqualTo("notes.txt");
        assertThat(new String(parts.getBodyPart(1).getInputStream().readAllBytes()))
            .isEqualTo("the notes");
        assertThat(started.event()).isNotEmpty();

        // a path instead of a token reads nothing
        client.send(
            new it.uhde.praksis.protocol.Request.RunCommand(
                "mail.send-message",
                java.util.Map.of(
                    "to",
                    "anna@example.org",
                    "subject",
                    "Smuggled",
                    "text",
                    "x",
                    "attachments",
                    java.util.List.of(
                        java.util.Map.of("name", "secret.txt", "upload", secret.toString()))),
                "test"));
        Thread.sleep(1500);
        assertThat(smtp.getReceivedMessages()).hasSize(1);
        assertThat(
                h.log().stream()
                    .map(l -> l.message())
                    .collect(java.util.stream.Collectors.joining()))
            .contains("not the token of an uploaded file");
      }
      h.stop(daemon);
    } finally {
      smtp.stop();
    }
  }
}
