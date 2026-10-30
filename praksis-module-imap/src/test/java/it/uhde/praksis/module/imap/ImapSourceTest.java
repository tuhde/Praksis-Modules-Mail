// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Event;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImapSourceTest {
  private Server server;
  private final List<Event> events = new CopyOnWriteArrayList<>();
  private final List<ImapSource> sources = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() {
    server = new Server();
  }

  @AfterEach
  void tearDown() {
    sources.forEach(ImapSource::stop);
    server.close();
  }

  private ImapSource start(Object... extra) throws Exception {
    ImapSource source = new ImapSource();
    source.configure(server.settings(extra));
    source.start(events::add);
    sources.add(source);
    return source;
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long end = System.nanoTime() + 10_000_000_000L;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("timed out");
      }
      Thread.sleep(10);
    }
  }

  private static String subject(Event e) {
    return (String) e.data().get("subject");
  }

  @Test
  void announcesUnseenMailsWithTheirHeaders() throws Exception {
    server.deliver(
        false,
        "From: Anna <anna@example.org>",
        "To: me@example.org",
        "Cc: bob@example.org",
        "Subject: =?UTF-8?B?R3LDvMOfZQ==?=",
        "Message-ID: <abc@example.org>",
        "Date: Tue, 27 Oct 2026 10:00:00 +0100",
        "List-Id: <news.lists.example.org>",
        "Precedence: bulk",
        "X-Spam-Flag: YES");
    start();
    await(() -> events.size() == 1);
    Event e = events.get(0);
    assertThat(e.type()).isEqualTo("mail.received");
    assertThat(e.source()).isEqualTo("imap");
    assertThat(e.depth()).isZero();
    assertThat(e.data())
        .containsEntry("folder", "INBOX")
        .containsEntry("from", "Anna <anna@example.org>")
        .containsEntry("to", "me@example.org")
        .containsEntry("cc", "bob@example.org")
        .containsEntry("subject", "Grüße")
        .containsEntry("message_id", "<abc@example.org>")
        .containsEntry("date", "Tue, 27 Oct 2026 10:00:00 +0100")
        .containsEntry("list_id", "<news.lists.example.org>")
        .containsEntry("precedence", "bulk")
        .containsEntry("x_spam_flag", "YES");
    assertThat((Long) e.data().get("size")).isPositive();
    MessageId id = MessageId.parse((String) e.data().get("message")).orElseThrow();
    assertThat(id.folder()).isEqualTo("INBOX");
    assertThat(id.uid()).isPositive();
    assertThat(id.messageId()).isEqualTo("<abc@example.org>");
    assertThat(e.key().orElseThrow())
        .isEqualTo("imap:me@127.0.0.1:INBOX:" + id.validity() + ":" + id.uid());
    assertThat(e.target()).isEqualTo("mail:" + e.key().orElseThrow());
  }

  @Test
  void headersThatAreMissingAreEmpty() throws Exception {
    server.deliver(false, "Subject: bare");
    start();
    await(() -> events.size() == 1);
    assertThat(events.get(0).data())
        .containsEntry("from", "")
        .containsEntry("list_id", "")
        .containsEntry("message_id", "");
  }

  @Test
  void theInitialSettingDecidesWhatIsHandledAtTheFirstStart() throws Exception {
    server.deliver(true, "Subject: seen");
    server.deliver(false, "Subject: unseen");

    start(); // unseen is the default
    await(() -> events.size() == 1);
    assertThat(subject(events.get(0))).isEqualTo("unseen");
    sources.get(0).stop();
    events.clear();

    start("initial", "all");
    await(() -> events.size() == 2);
    assertThat(events)
        .extracting(ImapSourceTest::subject)
        .containsExactlyInAnyOrder("seen", "unseen");
    sources.get(1).stop();
    events.clear();

    start("initial", "none");
    Thread.sleep(300);
    assertThat(events).isEmpty();
    server.deliver(true, "Subject: later");
    await(() -> events.size() == 1); // later mail is handled, seen or not
    assertThat(subject(events.get(0))).isEqualTo("later");
  }

  @Test
  void laterMailsAreAnnouncedOnceAndInOrder() throws Exception {
    start();
    Thread.sleep(150);
    server.deliver(false, "Subject: one");
    server.deliver(false, "Subject: two");
    await(() -> events.size() == 2);
    server.deliver(false, "Subject: three");
    await(() -> events.size() == 3);
    Thread.sleep(300); // many more polls
    assertThat(events).extracting(ImapSourceTest::subject).containsExactly("one", "two", "three");
  }

  @Test
  void aRestartAnnouncesWhatIsStillThereWithTheSameKeys() throws Exception {
    server.deliver(false, "Subject: waiting");
    ImapSource first = start();
    await(() -> events.size() == 1);
    first.stop();
    start();
    await(() -> events.size() == 2);
    assertThat(events.get(1).key()).isEqualTo(events.get(0).key());
    assertThat(events.get(1).id()).isNotEqualTo(events.get(0).id());
  }

  @Test
  void aServerThatCannotBeReachedOrRefusesTheLoginDoesNotStopTheSource() throws Exception {
    ImapSource wrong = start("password_command", List.of("sh", "-c", "printf wrong"));
    server.deliver(false, "Subject: x");
    Thread.sleep(400);
    assertThat(events).isEmpty();
    wrong.stop();

    // a source that could not log in keeps trying: it works once the password is right
    java.nio.file.Path secret = java.nio.file.Files.createTempFile("pw", ".txt");
    java.nio.file.Files.writeString(secret, "wrong");
    start("password_command", List.of("cat", secret.toString()));
    Thread.sleep(300);
    assertThat(events).isEmpty();
    java.nio.file.Files.writeString(secret, "secret");
    await(() -> events.size() == 1);
    java.nio.file.Files.delete(secret);
  }

  @Test
  void startNeedsSettings() {
    assertThatThrownBy(() -> new ImapSource().start(events::add))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining("not configured");
  }
}
