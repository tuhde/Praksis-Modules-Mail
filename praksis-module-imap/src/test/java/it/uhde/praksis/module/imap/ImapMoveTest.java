// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Event;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImapMoveTest {
  private Server server;
  private ImapMove move;
  private ImapSource source;
  private final List<Event> events = new CopyOnWriteArrayList<>();

  private static CapabilityContext context(boolean dryRun) {
    return new CapabilityContext() {
      @Override
      public boolean dryRun() {
        return dryRun;
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
  }

  @BeforeEach
  void setUp() throws Exception {
    server = new Server();
    move = new ImapMove();
    move.configure(server.settings());
  }

  @AfterEach
  void tearDown() {
    if (source != null) {
      source.stop();
    }
    server.close();
  }

  /** Delivers a mail and returns the id that the source gives it. */
  private String arrive(String... headers) throws Exception {
    int before = events.size();
    server.deliver(false, headers);
    if (source == null) {
      source = new ImapSource();
      source.configure(server.settings());
      source.start(events::add);
    }
    long end = System.nanoTime() + 10_000_000_000L;
    while (events.size() == before) {
      if (System.nanoTime() > end) {
        throw new AssertionError("the mail did not arrive");
      }
      Thread.sleep(10);
    }
    return (String) events.get(events.size() - 1).data().get("message");
  }

  private Map<String, Object> call(String message, String folder, boolean dryRun)
      throws CapabilityException {
    return move.invoke(context(dryRun), Map.of("message", message, "folder", folder));
  }

  private List<String> subjects(String folder) throws Exception {
    var store = new Connection(ImapSettings.parse(server.settings())).store();
    Folder f = store.getFolder(folder);
    if (!f.exists()) {
      return List.of();
    }
    f.open(Folder.READ_ONLY);
    try {
      List<String> out = new ArrayList<>();
      for (Message m : f.getMessages()) {
        out.add(m.getSubject());
      }
      return out;
    } finally {
      f.close(false);
      store.close();
    }
  }

  @Test
  void movesAMailToAFolderThatIsCreatedWhenMissing() throws Exception {
    String id = arrive("Subject: one", "Message-ID: <1@example.org>");
    arrive("Subject: two", "Message-ID: <2@example.org>");
    Map<String, Object> result = call(id, "Archive", false);

    assertThat(subjects("INBOX")).containsExactly("two");
    assertThat(subjects("Archive")).containsExactly("one");
    MessageId moved = MessageId.parse((String) result.get("message")).orElseThrow();
    assertThat(moved.folder()).isEqualTo("Archive");
    assertThat(moved.uid()).isPositive();
    assertThat(moved.messageId()).isEqualTo("<1@example.org>");
  }

  @Test
  void aRepeatedMoveSucceedsAndNamesTheSameMail() throws Exception {
    String id = arrive("Subject: one", "Message-ID: <1@example.org>");
    Map<String, Object> first = call(id, "Archive", false);
    assertThat(call(id, "Archive", false)).isEqualTo(first);
    assertThat(subjects("Archive")).containsExactly("one");
  }

  @Test
  void aMailThatIsNowhereIsAnError() throws Exception {
    String id = arrive("Subject: one", "Message-ID: <1@example.org>");
    call(id, "Archive", false);
    assertThatThrownBy(() -> call(id, "Elsewhere", false))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining("mail not found")
        .hasMessageContaining("not in Elsewhere");
  }

  @Test
  void aDryRunChangesNothing() throws Exception {
    String id = arrive("Subject: one", "Message-ID: <1@example.org>");
    Map<String, Object> result = call(id, "Archive", true);
    assertThat(subjects("INBOX")).containsExactly("one");
    assertThat(subjects("Archive")).isEmpty();
    assertThat(MessageId.parse((String) result.get("message")).orElseThrow().folder())
        .isEqualTo("Archive");
  }

  @Test
  void foldersCanBeNestedWithSlashes() throws Exception {
    String id = arrive("Subject: one", "Message-ID: <1@example.org>");
    Map<String, Object> result = call(id, "Archive/2026", false);
    assertThat(subjects("Archive.2026")).containsExactly("one");
    assertThat(MessageId.parse((String) result.get("message")).orElseThrow().folder())
        .isEqualTo("Archive/2026");
  }

  @Test
  void aMailFromAnotherNumberingOrAnythingElseIsRefused() throws Exception {
    String id = arrive("Subject: one", "Message-ID: <1@example.org>");
    MessageId real = MessageId.parse(id).orElseThrow();
    String stale =
        new MessageId(real.folder(), real.validity() + 1, real.uid(), real.messageId()).encode();
    assertThatThrownBy(() -> call(stale, "Archive", false)).hasMessageContaining("mail not found");
    assertThat(subjects("INBOX")).containsExactly("one");
    assertThatThrownBy(() -> call("/some/path", "Archive", false))
        .hasMessageContaining("is not the id of an IMAP mail");
  }

  @Test
  void aMailWithoutMessageIdMovesButCannotBeFoundAgain() throws Exception {
    String id = arrive("Subject: bare");
    Map<String, Object> result = call(id, "Archive", false);
    assertThat(subjects("Archive")).containsExactly("bare");
    // the server tells the new UID, so the result names the mail even without a Message-ID
    assertThat(MessageId.parse((String) result.get("message")).orElseThrow().uid()).isPositive();
    assertThatThrownBy(() -> call(id, "Archive", false)).hasMessageContaining("mail not found");
  }

  @Test
  void aServerWithoutMoveGetsCopyAndUidExpunge() throws Exception {
    move.useMove = false;
    String keep = arrive("Subject: keep", "Message-ID: <k@example.org>");
    String id = arrive("Subject: one", "Message-ID: <1@example.org>");
    // a mail the user marked as deleted must survive: only the moved one is expunged
    var store = new Connection(ImapSettings.parse(server.settings())).store();
    Folder inbox = store.getFolder("INBOX");
    inbox.open(Folder.READ_WRITE);
    inbox.getMessage(1).setFlag(jakarta.mail.Flags.Flag.DELETED, true);
    inbox.close(false);

    Map<String, Object> result = call(id, "Archive", false);

    assertThat(subjects("Archive")).containsExactly("one");
    assertThat(subjects("INBOX")).containsExactly("keep");
    assertThat(MessageId.parse((String) result.get("message")).orElseThrow().uid()).isPositive();
    assertThat(call(id, "Archive", false)).isEqualTo(result);
    assertThat(keep).isNotEmpty();
    store.close();
  }

  @Test
  void aServerWithNeitherMoveNorUidplusIsRefusedRatherThanRisked() throws Exception {
    move.useMove = false;
    move.useUidplus = false;
    String id = arrive("Subject: one", "Message-ID: <1@example.org>");
    assertThatThrownBy(() -> call(id, "Archive", false))
        .hasMessageContaining("neither MOVE nor UIDPLUS");
    assertThat(subjects("INBOX")).containsExactly("one");
  }

  @Test
  void needsSettings() {
    assertThatThrownBy(
            () -> new ImapMove().invoke(context(false), Map.of("message", "x", "folder", "y")))
        .hasMessageContaining("not configured");
  }
}
