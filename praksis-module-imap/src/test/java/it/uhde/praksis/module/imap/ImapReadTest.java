// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Event;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImapReadTest {
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

  private Server server;
  private ImapRead.Folders folders;
  private ImapRead.Listing listing;
  private ImapRead.Get get;

  @BeforeEach
  void setUp() throws Exception {
    server = new Server();
    folders = new ImapRead.Folders();
    listing = new ImapRead.Listing();
    get = new ImapRead.Get();
    for (ImapRead r : List.of(folders, listing, get)) {
      r.configure(server.settings());
    }
  }

  @AfterEach
  void tearDown() {
    folders.close();
    listing.close();
    get.close();
    server.close();
  }

  private static Map<String, Object> args(Object... kv) {
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> items(String folder, Object limit) throws Exception {
    return (List<Map<String, Object>>)
        listing
            .invoke(
                CONTEXT,
                limit == null ? args("folder", folder) : args("folder", folder, "limit", limit))
            .get("items");
  }

  @Test
  @SuppressWarnings("unchecked")
  void theFoldersComeWithUnreadAndTotal() throws Exception {
    server.createFolder("Lists");
    server.deliver(false, "Subject: one", "Message-ID: <1@x>");
    server.deliver(true, "Subject: two", "Message-ID: <2@x>");

    List<Map<String, Object>> list =
        (List<Map<String, Object>>) folders.invoke(CONTEXT, Map.of()).get("folders");

    assertThat(list).extracting(f -> f.get("name")).contains("INBOX", "Lists");
    assertThat(list.get(0))
        .containsEntry("name", "INBOX")
        .containsEntry("unread", 1L)
        .containsEntry("total", 2L);
    Map<String, Object> lists =
        list.stream().filter(f -> f.get("name").equals("Lists")).findFirst().get();
    assertThat(lists).containsEntry("total", 0L);
  }

  @Test
  void theNewestMailsComeFirstWithWhatAListShows() throws Exception {
    server.deliver(
        true,
        "From: Ann <ann@example.org>",
        "Subject: old",
        "Message-ID: <1@x>",
        "Date: Sat, 31 Jan 2026 10:00:00 +0000");
    server.deliver(
        false,
        "From: Bob <bob@example.org>",
        "Subject: new",
        "Message-ID: <2@x>",
        "Date: Sat, 31 Jan 2026 11:00:00 +0000");

    List<Map<String, Object>> inbox = items("INBOX", null);

    assertThat(inbox).extracting(i -> i.get("subject")).containsExactly("new", "old");
    assertThat(inbox.get(0))
        .containsEntry("unread", true)
        .containsEntry("time", java.time.Instant.parse("2026-01-31T11:00:00Z"));
    assertThat((String) inbox.get(0).get("from")).contains("bob@example.org");
    assertThat(inbox.get(1)).containsEntry("unread", false);
    assertThat(MessageId.parse((String) inbox.get(0).get("id"))).isPresent();
    assertThat(items("INBOX", 1L)).hasSize(1);
    assertThat(listing.invoke(CONTEXT, args("folder", "INBOX", "limit", 1L)).get("count"))
        .isEqualTo(2L);
    assertThat(items("Nowhere", null)).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void aMailIsReadForShowingWithoutMarkingItRead() throws Exception {
    server.deliverRaw(
        false,
        """
        From: Ann <ann@example.org>
        To: me@example.org
        Subject: Hello
        Message-ID: <3@x>
        Date: Sat, 31 Jan 2026 10:00:00 +0000
        MIME-Version: 1.0
        Content-Type: multipart/mixed; boundary="b"

        --b
        Content-Type: multipart/alternative; boundary="a"

        --a
        Content-Type: text/plain; charset=utf-8

        the text
        --a
        Content-Type: text/html; charset=utf-8

        <p>the html</p>
        --a--

        --b
        Content-Type: application/pdf; name="plan.pdf"
        Content-Disposition: attachment; filename="plan.pdf"
        Content-Transfer-Encoding: base64

        AAECAwQF
        --b--
        """);
    String id = (String) items("INBOX", null).get(0).get("id");

    Map<String, Object> mail = get.invoke(CONTEXT, args("message", id));

    assertThat(mail)
        .containsEntry("subject", "Hello")
        .containsEntry("message_id", "<3@x>")
        .containsEntry("time", java.time.Instant.parse("2026-01-31T10:00:00Z"));
    assertThat((String) mail.get("text")).startsWith("the text");
    assertThat((String) mail.get("html")).contains("<p>the html</p>");
    assertThat((List<Map<String, Object>>) mail.get("attachments"))
        .singleElement()
        .satisfies(
            a ->
                assertThat(a)
                    .containsEntry("name", "plan.pdf")
                    .containsEntry("type", "application/pdf"));
    // reading did not mark it as read
    assertThat(items("INBOX", null).get(0)).containsEntry("unread", true);
  }

  @Test
  void aMailThatIsGoneOrNotAnImapIdIsRefused() throws Exception {
    server.deliver(false, "Subject: one", "Message-ID: <1@x>");
    String id = (String) items("INBOX", null).get(0).get("id");
    MessageId real = MessageId.parse(id).orElseThrow();
    String stale =
        new MessageId(real.folder(), real.validity() + 1, real.uid(), real.messageId()).encode();
    assertThatThrownBy(() -> get.invoke(CONTEXT, args("message", stale)))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining("mail not found");
    assertThatThrownBy(() -> get.invoke(CONTEXT, args("message", "/some/path")))
        .hasMessageContaining("is not the id of an IMAP mail");
    assertThatThrownBy(() -> new ImapRead.Get().invoke(CONTEXT, args("message", id)))
        .hasMessageContaining("not configured");
  }
}
