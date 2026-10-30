// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.Event;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImapIdleTest {
  private static final CapabilityContext CONTEXT =
      new CapabilityContext() {
        @Override
        public boolean dryRun() {
          return false;
        }

        @Override
        public String idempotencyKey() {
          return "k";
        }

        @Override
        public Optional<Event> event() {
          return Optional.empty();
        }
      };

  @TempDir Path data;

  private Server server;
  private ImapSource source;
  private ImapRead.Listing listing;
  private final List<Event> events = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() {
    server = new Server();
  }

  @AfterEach
  void tearDown() {
    if (source != null) {
      source.stop();
    }
    if (listing != null) {
      listing.close();
    }
    server.close();
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

  private List<Event> changes() {
    return events.stream().filter(e -> e.type().equals("mail.changed")).toList();
  }

  @Test
  @SuppressWarnings("unchecked")
  void aChangeOnTheServerIsSeenAtOnceAndAnnouncedAsAnEventWithoutWaitingForThePoll()
      throws Exception {
    // a poll every minute: whatever arrives within seconds was found by IDLE
    source = new ImapSource();
    source.configure(server.settings("poll_interval_ms", 60_000L, "initial", "none"));
    source.useDataDirectory(data);
    source.start(events::add);
    listing = new ImapRead.Listing();
    listing.configure(server.settings("poll_interval_ms", 60_000L));
    listing.useDataDirectory(data);

    // wait until the connection waits for news: the first mail then must be noticed
    Thread.sleep(500);
    server.deliver(false, "From: a@x", "Subject: one", "Message-ID: <1@x>");
    await(() -> events.stream().anyMatch(e -> e.type().equals("mail.received")));
    await(() -> !changes().isEmpty());
    assertThat(changes().get(0).data())
        .containsEntry("folder", "INBOX")
        .containsEntry("added", 1L)
        .containsEntry("removed", 0L)
        .containsEntry("changed", 0L);

    // the capabilities, which share the cache, now answer without the server (it is watched)
    Map<String, Object> answer = listing.invoke(CONTEXT, Map.of("folder", "INBOX"));
    assertThat((List<?>) answer.get("items")).hasSize(1);

    @SuppressWarnings("unchecked")
    String id = (String) ((List<Map<String, Object>>) answer.get("items")).get(0).get("id");

    // and a removed mail
    int before = changes().size();
    var delete = new ImapEdit.Delete();
    delete.configure(server.settings("cache", false));
    delete.invoke(CONTEXT, Map.of("message", id));
    delete.close();
    await(() -> changes().size() > before);
    assertThat(changes().get(before).data()).containsEntry("removed", 1L);
    assertThat((List<?>) listing.invoke(CONTEXT, Map.of("folder", "INBOX")).get("items")).isEmpty();
  }

  @Test
  void withoutACacheTheSourceStillWorksAndSaysNothingAboutChanges() throws Exception {
    source = new ImapSource();
    source.configure(server.settings("poll_interval_ms", 60_000L, "initial", "none"));
    source.start(events::add);
    Thread.sleep(500);
    server.deliver(false, "From: a@x", "Subject: one", "Message-ID: <1@x>");

    await(() -> events.stream().anyMatch(e -> e.type().equals("mail.received")));
    assertThat(changes()).isEmpty();
  }
}
