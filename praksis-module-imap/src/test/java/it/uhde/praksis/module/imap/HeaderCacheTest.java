// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Event;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeaderCacheTest {
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
  private final List<Capability> opened = new ArrayList<>();

  @BeforeEach
  void setUp() {
    server = new Server();
  }

  @AfterEach
  void tearDown() {
    opened.forEach(Capability::close);
    server.close();
  }

  /** A capability with the cache (unless {@code cached} is false) and these extra settings. */
  private <T extends Capability> T make(T capability, boolean cached, Object... extra)
      throws Exception {
    capability.configure(server.settings(extra));
    if (cached) {
      capability.useDataDirectory(data);
    }
    opened.add(capability);
    return capability;
  }

  private static Map<String, Object> args(Object... kv) {
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> items(Map<String, Object> answer) {
    return (List<Map<String, Object>>) answer.get("items");
  }

  private List<Map<String, Object>> list(Capability listing, Object... kv) throws Exception {
    Map<String, Object> a = args("folder", "INBOX");
    a.putAll(args(kv));
    return items(listing.invoke(CONTEXT, a));
  }

  private void three() throws Exception {
    server.deliver(true, "From: carol@x", "Subject: Bravo", "Message-ID: <1@x>");
    server.deliver(true, "From: alice@x", "Subject: Charlie", "Message-ID: <2@x>");
    server.deliver(false, "From: bob@x", "Subject: alpha", "Message-ID: <3@x>");
  }

  @Test
  void aFolderIsListedFromTheCacheEvenWhenTheServerIsGone() throws Exception {
    three();
    var listing = make(new ImapRead.Listing(), true);
    assertThat(list(listing)).hasSize(3);

    server.close();

    // fresh enough: no connection is tried at all
    assertThat(list(listing))
        .extracting(m -> m.get("subject"))
        .containsExactly("alpha", "Charlie", "Bravo");
    assertThat(list(listing, "sort", "from"))
        .extracting(m -> m.get("from"))
        .containsExactly("alice@x", "bob@x", "carol@x");
  }

  @Test
  void aFolderThatIsTooOldIsStillListedWhenTheServerCannotBeReached() throws Exception {
    three();
    var listing = make(new ImapRead.Listing(), true, "cache_max_age_ms", 0L);
    assertThat(list(listing)).hasSize(3);
    server.close();

    assertThat(list(listing)).hasSize(3);
    // a folder that was never cached is an error, as without the cache
    assertThatThrownBy(() -> list(listing, "folder", "Other"))
        .isInstanceOf(CapabilityException.class);
  }

  @Test
  void withoutADirectoryOrWithTheCacheOffTheServerAnswers() throws Exception {
    three();
    var plain = make(new ImapRead.Listing(), false);
    var off = make(new ImapRead.Listing(), true, "cache", false);
    assertThat(list(plain)).hasSize(3);
    assertThat(list(off)).hasSize(3);
    assertThat(data).isEmptyDirectory();
    server.close();
    assertThatThrownBy(() -> list(plain)).isInstanceOf(CapabilityException.class);
  }

  @Test
  void anOldFolderIsSyncedAgainAndSeesWhatChangedOnTheServer() throws Exception {
    three();
    var listing = make(new ImapRead.Listing(), true, "cache_max_age_ms", 0L);
    var other = make(new ImapEdit.Mark(), false);
    var gone = make(new ImapEdit.Delete(), false);
    var plain = make(new ImapRead.Listing(), false);
    assertThat(list(listing)).hasSize(3);

    server.deliver(false, "From: dave@x", "Subject: Delta", "Message-ID: <4@x>");
    assertThat(list(listing)).hasSize(4);

    String alpha =
        (String)
            list(listing).stream()
                .filter(m -> m.get("subject").equals("alpha"))
                .findFirst()
                .orElseThrow()
                .get("id");
    other.invoke(CONTEXT, args("message", alpha, "seen", true, "flagged", true));
    assertThat(list(listing).stream().filter(m -> m.get("subject").equals("alpha")))
        .singleElement()
        .satisfies(
            m -> assertThat(m).containsEntry("unread", false).containsEntry("starred", true));

    String bravo =
        (String)
            list(listing).stream()
                .filter(m -> m.get("subject").equals("Bravo"))
                .findFirst()
                .orElseThrow()
                .get("id");
    gone.invoke(CONTEXT, args("message", bravo));
    assertThat(list(listing)).extracting(m -> m.get("subject")).doesNotContain("Bravo").hasSize(3);

    // and it is the same as what the server says
    assertThat(list(listing)).isEqualTo(list(plain));
  }

  @Test
  void changesMadeThroughTheCapabilitiesAreInTheCacheAtOnce() throws Exception {
    three();
    var listing = make(new ImapRead.Listing(), true);
    var mark = make(new ImapEdit.Mark(), true);
    var delete = make(new ImapEdit.Delete(), true);
    var move = make(new ImapMove(), true);
    assertThat(list(listing)).hasSize(3);
    assertThat(list(listing, "folder", "Archive")).isEmpty();

    String alpha = (String) list(listing).get(0).get("id");
    mark.invoke(CONTEXT, args("message", alpha, "seen", true, "flagged", true));
    server.close(); // nothing below may need the server's answer to a list
    assertThat(list(listing).get(0)).containsEntry("unread", false).containsEntry("starred", true);
    assertThat(alpha).isNotEmpty();
  }

  @Test
  void deletingAndMovingTakeTheMailOutOfTheCachedFolder() throws Exception {
    three();
    var listing = make(new ImapRead.Listing(), true);
    var delete = make(new ImapEdit.Delete(), true);
    var move = make(new ImapMove(), true);
    assertThat(list(listing)).hasSize(3);

    String newest = (String) list(listing).get(0).get("id");
    String middle = (String) list(listing).get(1).get("id");
    delete.invoke(CONTEXT, args("message", newest));
    move.invoke(CONTEXT, args("message", middle, "folder", "Archive"));

    // both are out of the cached INBOX without a new sync, and the target is read from the server
    assertThat(list(listing)).extracting(m -> m.get("subject")).containsExactly("Bravo");
    assertThat(list(listing, "folder", "Archive"))
        .extracting(m -> m.get("subject"))
        .containsExactly("Charlie");
  }

  @Test
  void searchByFromOrSubjectUsesTheCacheAndSearchByTextTheServer() throws Exception {
    server.deliverRaw(
        true, "From: carol@x\nSubject: Invoice March\nMessage-ID: <1@x>\n\nnothing\n");
    server.deliverRaw(
        true, "From: dave@x\nSubject: Hello\nMessage-ID: <2@x>\n\nthe invoice is attached\n");
    var search = make(new ImapRead.Search(), true);
    var listing = make(new ImapRead.Listing(), true);
    list(listing); // fills the cache

    Map<String, Object> answer =
        search.invoke(CONTEXT, args("folder", "INBOX", "query", "INVOICE", "in", "subject"));
    assertThat(items(answer)).extracting(m -> m.get("from")).containsExactly("carol@x");
    assertThat(answer).containsEntry("count", 1L);
    assertThat(
            items(search.invoke(CONTEXT, args("folder", "INBOX", "query", "DAVE", "in", "from"))))
        .hasSize(1);
    // the text of the mails is not in the cache
    assertThat(
            items(
                search.invoke(CONTEXT, args("folder", "INBOX", "query", "invoice", "in", "body"))))
        .extracting(m -> m.get("from"))
        .containsExactly("dave@x");
    server.close();
    assertThat(
            items(search.invoke(CONTEXT, args("folder", "INBOX", "query", "dave", "in", "from"))))
        .hasSize(1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void theNumbersOfTheFoldersComeFromTheCacheOnceTheyAreKnown() throws Exception {
    three();
    var listing = make(new ImapRead.Listing(), true);
    var folders = make(new ImapRead.Folders(), true);
    list(listing);

    var first = (List<Map<String, Object>>) folders.invoke(CONTEXT, Map.of()).get("folders");
    assertThat(first.get(0)).containsEntry("total", 3L).containsEntry("unread", 1L);

    // a mail arrives; the cache does not know yet, and the folder list does not ask the server
    server.deliver(false, "From: eve@x", "Subject: new", "Message-ID: <9@x>");
    var second = (List<Map<String, Object>>) folders.invoke(CONTEXT, Map.of()).get("folders");
    assertThat(second.get(0)).containsEntry("total", 3L).containsEntry("unread", 1L);
  }

  @Test
  void aFolderWithANewUidValidityIsEmptiedAndFilledAgain() throws Exception {
    three();
    var listing = make(new ImapRead.Listing(), true, "cache_max_age_ms", 0L);
    List<Map<String, Object>> before = list(listing);
    assertThat(before).hasSize(3);

    // the cache remembers another validity, as if the server had renumbered the folder
    Path file;
    try (var files = Files.list(data)) {
      file = files.filter(f -> f.toString().endsWith(".cache.db")).findFirst().orElseThrow();
    }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + file);
        var s = db.createStatement()) {
      s.executeUpdate("UPDATE folder SET validity = validity + 1000");
      s.executeUpdate("UPDATE message SET subject = 'stale', subject_l = 'stale'");
    }

    // the stale rows are gone, not merged with the new ones
    assertThat(list(listing)).isEqualTo(before);
  }

  @Test
  void aWatchedFolderIsTrustedOnlyForALimitedTime() throws Exception {
    three();
    var listing = make(new ImapRead.Listing(), true, "cache_max_age_ms", 0L);
    list(listing); // synced
    HeaderCache cache = HeaderCache.open(data, ImapSettings.parse(server.settings()), "default");
    try {
      Thread.sleep(5);
      assertThat(cache.fresh("INBOX")).isFalse(); // not watched, and the age limit is 0
      cache.watching("INBOX", true);
      assertThat(cache.fresh("INBOX")).isTrue(); // watched: trusted without a limit of its own
      cache.watchedMaxAge(0);
      Thread.sleep(5);
      assertThat(cache.fresh("INBOX")).isFalse(); // but not for ever
      cache.watching("INBOX", false);
    } finally {
      cache.release();
    }
  }

  @Test
  void aFileThatIsNotACacheIsThrownAway() throws Exception {
    three();
    try (var files = Files.list(data)) {
      assertThat(files).isEmpty();
    }
    Files.writeString(data.resolve("me@127.0.0.1.cache.db"), "this is not a database");

    var listing = make(new ImapRead.Listing(), true);

    assertThat(list(listing)).hasSize(3);
  }

  @Test
  void theCacheSettingsAreChecked() {
    assertThatThrownBy(() -> new ImapRead.Listing().configure(server.settings("cache", "yes")))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining("'cache' must be true or false");
    assertThatThrownBy(
            () -> new ImapRead.Listing().configure(server.settings("cache_max_age_ms", -1L)))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining("cache_max_age_ms");
  }
}
