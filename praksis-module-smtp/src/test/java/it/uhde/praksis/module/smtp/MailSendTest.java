// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.smtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Event;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MailSendTest {
  private static CapabilityContext context(String key, boolean dry) {
    return new CapabilityContext() {
      @Override
      public boolean dryRun() {
        return dry;
      }

      @Override
      public String idempotencyKey() {
        return key;
      }

      @Override
      public Optional<Event> event() {
        return Optional.empty();
      }
    };
  }

  @TempDir Path data;

  private Server server;
  private final List<Capability> opened = new ArrayList<>();
  private int calls;

  @BeforeEach
  void setUp() {
    server = new Server();
  }

  @AfterEach
  void tearDown() {
    opened.forEach(Capability::close);
    server.close();
  }

  private MailSend send(Object... extra) throws Exception {
    MailSend s = new MailSend();
    s.configure(server.settings(extra));
    s.useDataDirectory(data);
    s.useUploadsDirectory(uploads());
    opened.add(s);
    return s;
  }

  private Path uploads() throws Exception {
    return java.nio.file.Files.createDirectories(data.resolve("uploads-dir"));
  }

  private static Map<String, Object> args(Object... kv) {
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  private Map<String, Object> go(MailSend s, Object... kv) throws Exception {
    return s.invoke(context("key-" + calls++, false), args(kv));
  }

  private static MimeMessage parse(byte[] raw) throws Exception {
    return new MimeMessage(
        Session.getInstance(new java.util.Properties()), new ByteArrayInputStream(raw));
  }

  @Test
  void aMailIsSentToEveryoneAndTheAnswerIsTheMailAsItWentOut() throws Exception {
    Map<String, Object> answer =
        go(
            send(),
            "to",
            "Anna <anna@example.org>",
            "cc",
            "bob@example.org",
            "bcc",
            "carl@example.org",
            "subject",
            "Grüße aus Köln",
            "text",
            "Hello\nwith ümlauts",
            "in_reply_to",
            "<orig@example.org>",
            "references",
            "<a@x> <orig@example.org>");

    MimeMessage[] got = server.received();
    assertThat(got).hasSize(3); // one per recipient, the bcc one too
    MimeMessage m = got[0];
    assertThat(m.getSubject()).isEqualTo("Grüße aus Köln");
    assertThat(m.getFrom()[0].toString()).contains("me@example.org");
    assertThat(m.getHeader("Message-ID")[0]).isEqualTo(answer.get("message_id"));
    assertThat(m.getHeader("In-Reply-To")[0]).isEqualTo("<orig@example.org>");
    assertThat(m.getHeader("References")[0]).isEqualTo("<a@x> <orig@example.org>");
    assertThat(m.getHeader("Bcc")).isNull(); // nobody sees who was on bcc
    assertThat(m.getContent().toString()).contains("with ümlauts");

    assertThat(answer).containsEntry("recipients", 3L);
    MimeMessage copy = parse((byte[]) answer.get("raw"));
    assertThat(copy.getSubject()).isEqualTo("Grüße aus Köln");
    assertThat(copy.getHeader("Bcc")).isNull();
    assertThat(copy.getHeader("Message-ID")[0]).isEqualTo(answer.get("message_id"));
  }

  @Test
  void aMailWithHtmlAndAnAttachmentHasTheRightParts() throws Exception {
    go(
        send(),
        "to",
        "anna@example.org",
        "subject",
        "files",
        "text",
        "plain",
        "html",
        "<p>rich</p>",
        "attachments",
        List.of(
            Map.of(
                "name",
                "data.bin",
                "type",
                "application/octet-stream",
                "content",
                new byte[] {0, 1, 2, 3})));

    MimeMessage m = server.received()[0];
    assertThat(m.isMimeType("multipart/mixed")).isTrue();
    Multipart outer = (Multipart) m.getContent();
    assertThat(outer.getCount()).isEqualTo(2);
    assertThat(outer.getBodyPart(0).isMimeType("multipart/alternative")).isTrue();
    assertThat(outer.getBodyPart(1).getFileName()).isEqualTo("data.bin");
    assertThat(outer.getBodyPart(1).getInputStream().readAllBytes()).containsExactly(0, 1, 2, 3);
  }

  @Test
  void nothingThatCouldInjectAHeaderIsSent() throws Exception {
    MailSend s = send();
    String evil = "x\r\nBcc: victim@example.org";
    for (String field : List.of("subject", "to", "cc", "from", "in_reply_to", "references")) {
      Map<String, Object> a = args("to", "anna@example.org", "subject", "s", "text", "t");
      a.put(field, evil);
      assertThatThrownBy(() -> s.invoke(context("k-" + field, false), a))
          .as(field)
          .isInstanceOf(CapabilityException.class)
          .hasMessageContaining("line break");
    }
    assertThatThrownBy(
            () ->
                go(
                    s,
                    "to",
                    "anna@example.org",
                    "subject",
                    "s",
                    "text",
                    "t",
                    "attachments",
                    List.of(Map.of("name", "a\r\nX: y", "content", new byte[1]))))
        .hasMessageContaining("not allowed");
    assertThat(server.received()).isEmpty();
  }

  @Test
  void addressesAndRecipientsAreChecked() throws Exception {
    MailSend s = send("max_recipients", 2L);
    assertThatThrownBy(() -> go(s, "to", "not an address", "subject", "s", "text", "t"))
        .hasMessageContaining("to");
    assertThatThrownBy(() -> go(s, "to", "nobody", "subject", "s", "text", "t"))
        .hasMessageContaining("is not a list of addresses");
    assertThatThrownBy(() -> go(s, "subject", "s", "text", "t"))
        .hasMessageContaining("at least one recipient");
    assertThatThrownBy(() -> go(s, "to", "anna@example.org", "subject", "s"))
        .hasMessageContaining("text or an html body");
    assertThatThrownBy(
            () ->
                go(
                    s,
                    "to",
                    "anna@example.org, bob@example.org, carl@example.org",
                    "subject",
                    "s",
                    "text",
                    "t"))
        .hasMessageContaining("at most 2 recipients");
    assertThat(server.received()).isEmpty();
  }

  @Test
  void theSenderIsTheConfiguredOneOrAnAllowedOne() throws Exception {
    MailSend s = send("allowed_from", List.of("alias@example.org"));
    assertThatThrownBy(
            () ->
                go(
                    s,
                    "to",
                    "anna@example.org",
                    "subject",
                    "s",
                    "text",
                    "t",
                    "from",
                    "boss@example.org"))
        .hasMessageContaining("may not send as boss@example.org");
    go(
        s,
        "to",
        "anna@example.org",
        "subject",
        "s",
        "text",
        "t",
        "from",
        "Alias <ALIAS@example.org>");
    go(s, "to", "anna@example.org", "subject", "s", "text", "t", "from", "me@example.org");
    assertThat(server.received()).hasSize(2);
    assertThat(server.received()[0].getFrom()[0].toString())
        .containsIgnoringCase("alias@example.org");
  }

  @Test
  void aMailTooBigIsRefused() throws Exception {
    MailSend s = send("max_bytes", 2048L);
    assertThatThrownBy(
            () -> go(s, "to", "anna@example.org", "subject", "s", "text", "x".repeat(5000)))
        .hasMessageContaining("more than the limit of 2048");
  }

  @Test
  void noMoreMailsPerHourThanTheLimitAndAnAttemptThatSendsNothingDoesNotCount() throws Exception {
    MailSend s = send("max_per_hour", 2L);
    s.invoke(context("dry", true), args("to", "anna@example.org", "subject", "s", "text", "t"));
    assertThat(server.received()).isEmpty();

    go(s, "to", "anna@example.org", "subject", "1", "text", "t");
    go(s, "to", "anna@example.org", "subject", "2", "text", "t");
    assertThatThrownBy(() -> go(s, "to", "anna@example.org", "subject", "3", "text", "t"))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining("limit of 2 mails per hour")
        .hasMessageContaining("the next can be sent at");
    assertThat(server.received()).hasSize(2);

    // a daemon that is started again does not forget what was sent
    s.close();
    MailSend again = send("max_per_hour", 2L);
    assertThatThrownBy(() -> go(again, "to", "anna@example.org", "subject", "4", "text", "t"))
        .hasMessageContaining("limit of 2 mails per hour");
    // and a different account has a limit of its own
    MailSend other = new MailSend();
    other.configure(
        server.settings(
            "user",
            "anna",
            "password_command",
            List.of("sh", "-c", "printf x"),
            "max_per_hour",
            2L));
    other.useDataDirectory(data);
    opened.add(other);
    go(other, "to", "bob@example.org", "subject", "5", "text", "t");
  }

  @Test
  void aCallThatIsRepeatedAfterACrashSendsNothingAgain() throws Exception {
    MailSend s = send();
    Map<String, Object> a = args("to", "anna@example.org", "subject", "once", "text", "t");

    Map<String, Object> first = s.invoke(context("same-key", false), a);
    s.close();
    Map<String, Object> second = send().invoke(context("same-key", false), a);

    assertThat(server.received()).hasSize(1);
    assertThat(second).containsEntry("message_id", first.get("message_id"));
    assertThat((byte[]) second.get("raw")).isEqualTo((byte[]) first.get("raw"));
  }

  @Test
  void aDryRunChecksEverythingAndSendsNothing() throws Exception {
    MailSend s = send();
    Map<String, Object> answer =
        s.invoke(context("d", true), args("to", "anna@example.org", "subject", "s", "text", "t"));
    assertThat(answer.get("message_id")).asString().startsWith("<").endsWith("@example.org>");
    assertThat(server.received()).isEmpty();
    assertThatThrownBy(
            () -> s.invoke(context("d2", true), args("to", "bad", "subject", "s", "text", "t")))
        .isInstanceOf(CapabilityException.class);
    // the key of a dry run is not remembered as a send
    go(s, "to", "anna@example.org", "subject", "s", "text", "t");
    assertThat(server.received()).hasSize(1);
  }

  @Test
  void aWrongPasswordIsRefusedAndNeverShown() throws Exception {
    MailSend s = send("password_command", List.of("sh", "-c", "printf wrong-secret"));
    assertThatThrownBy(() -> go(s, "to", "anna@example.org", "subject", "s", "text", "t"))
        .isInstanceOf(CapabilityException.class)
        .satisfies(
            e -> {
              assertThat(((CapabilityException) e).retryable()).isFalse();
              assertThat(e.getMessage()).doesNotContain("wrong-secret").doesNotContain("secret");
            });
    assertThat(server.received()).isEmpty();
  }

  @Test
  void composeMakesTheMailWithoutSendingAndAllowsADraft() throws Exception {
    MailCompose compose = new MailCompose();
    compose.configure(server.settings());

    Map<String, Object> draft =
        compose.invoke(
            context("c", false), args("subject", "half done", "text", "to be continued"));

    assertThat(server.received()).isEmpty();
    MimeMessage m = parse((byte[]) draft.get("raw"));
    assertThat(m.getSubject()).isEqualTo("half done");
    assertThat(m.getRecipients(jakarta.mail.Message.RecipientType.TO)).isNull();
    assertThat(draft).containsEntry("recipients", 0L);
    assertThat(new String((byte[]) draft.get("raw"), StandardCharsets.UTF_8))
        .contains("X-Mailer: Praksis");
    // the checks that matter are the same
    assertThatThrownBy(() -> compose.invoke(context("c", false), args("to", "a@b.c\r\nBcc: x@y.z")))
        .hasMessageContaining("line break");
  }

  @Test
  void aFileThatAPersonChoseIsAttachedByItsTokenAndNothingElseCanBeRead() throws Exception {
    MailSend s = send();
    String token = java.util.UUID.randomUUID().toString();
    java.nio.file.Files.write(uploads().resolve(token + ".part"), new byte[] {9, 8, 7});
    // a file outside of the uploads directory that a rule might want to attach
    Path secret = java.nio.file.Files.writeString(data.resolve("secret.txt"), "top secret");

    go(
        s,
        "to",
        "anna@example.org",
        "subject",
        "with file",
        "text",
        "see attached",
        "attachments",
        List.of(Map.of("name", "notes.txt", "type", "text/plain", "upload", token)));
    jakarta.mail.Multipart parts = (jakarta.mail.Multipart) server.received()[0].getContent();
    assertThat(parts.getBodyPart(1).getFileName()).isEqualTo("notes.txt");
    assertThat(parts.getBodyPart(1).getInputStream().readAllBytes()).containsExactly(9, 8, 7);

    for (String bad :
        List.of(
            "../secret",
            secret.toString(),
            "../../etc/passwd",
            token + "/../x",
            token.toUpperCase().replace("-", "_"),
            "")) {
      assertThatThrownBy(
              () ->
                  go(
                      s,
                      "to",
                      "anna@example.org",
                      "subject",
                      "x",
                      "text",
                      "x",
                      "attachments",
                      List.of(Map.of("name", "a.txt", "upload", bad))))
          .as(bad)
          .isInstanceOf(CapabilityException.class)
          .hasMessageContaining("not the token");
    }
    // a token of a file that is not there, a link, and a file that is too big
    assertThatThrownBy(
            () ->
                go(
                    s,
                    "to",
                    "anna@example.org",
                    "subject",
                    "x",
                    "text",
                    "x",
                    "attachments",
                    List.of(
                        Map.of("name", "a.txt", "upload", java.util.UUID.randomUUID().toString()))))
        .hasMessageContaining("is not there");
    String linked = java.util.UUID.randomUUID().toString();
    java.nio.file.Files.createSymbolicLink(uploads().resolve(linked + ".part"), secret);
    assertThatThrownBy(
            () ->
                go(
                    s,
                    "to",
                    "anna@example.org",
                    "subject",
                    "x",
                    "text",
                    "x",
                    "attachments",
                    List.of(Map.of("name", "a.txt", "upload", linked))))
        .hasMessageContaining("is not there");
    String big = java.util.UUID.randomUUID().toString();
    java.nio.file.Files.write(uploads().resolve(big + ".part"), new byte[5000]);
    MailSend small = send("max_bytes", 2048L);
    assertThatThrownBy(
            () ->
                go(
                    small,
                    "to",
                    "anna@example.org",
                    "subject",
                    "x",
                    "text",
                    "x",
                    "attachments",
                    List.of(Map.of("name", "a.bin", "upload", big))))
        .hasMessageContaining("more than the limit of 2048");
    assertThat(server.received()).hasSize(1);
    assertThatThrownBy(
            () ->
                go(
                    s,
                    "to",
                    "anna@example.org",
                    "subject",
                    "x",
                    "text",
                    "x",
                    "attachments",
                    List.of(Map.of("name", "a.bin"))))
        .hasMessageContaining("neither content nor an upload");
  }
}
