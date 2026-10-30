// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Event;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Several accounts in one module: [module.imap.work] and [module.imap.private]. */
class AccountsTest {
  private static CapabilityContext context(boolean dry) {
    return new CapabilityContext() {
      @Override
      public boolean dryRun() {
        return dry;
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

  @TempDir Path data;

  private Server work;
  private Server home;
  private final List<Capability> opened = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    work = new Server();
    home = new Server();
    work.deliver(false, "From: boss@x", "Subject: from work", "Message-ID: <w1@x>");
    home.deliver(false, "From: mum@x", "Subject: from home", "Message-ID: <h1@x>");
  }

  @AfterEach
  void tearDown() {
    opened.forEach(Capability::close);
    work.close();
    home.close();
  }

  private Map<String, Object> both() {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("work", work.settings());
    m.put("private", home.settings());
    return m;
  }

  private <T extends Capability> T make(T capability) throws Exception {
    capability.configure(both());
    capability.useDataDirectory(data);
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

  @Test
  void aCallWithoutAMailSaysWhichAccountOrIsToldToChoose() throws Exception {
    var list = make(new ImapRead.Listing());

    assertThatThrownBy(() -> list.invoke(context(false), args("folder", "INBOX")))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining("there are several accounts")
        .hasMessageContaining("work")
        .hasMessageContaining("private");
    assertThatThrownBy(
            () -> list.invoke(context(false), args("folder", "INBOX", "account", "nope")))
        .hasMessageContaining("there is no account 'nope'");

    assertThat(items(list.invoke(context(false), args("folder", "INBOX", "account", "work"))))
        .extracting(m -> m.get("subject"))
        .containsExactly("from work");
    assertThat(items(list.invoke(context(false), args("folder", "INBOX", "account", "private"))))
        .extracting(m -> m.get("subject"))
        .containsExactly("from home");
  }

  @Test
  void aMailNamesItsAccountAndEveryCapabilityFindsItFromTheId() throws Exception {
    var list = make(new ImapRead.Listing());
    var get = make(new ImapRead.Get());
    var mark = make(new ImapEdit.Mark());
    var move = make(new ImapMove());
    String workId =
        (String)
            items(list.invoke(context(false), args("folder", "INBOX", "account", "work")))
                .get(0)
                .get("id");
    String homeId =
        (String)
            items(list.invoke(context(false), args("folder", "INBOX", "account", "private")))
                .get(0)
                .get("id");

    assertThat(workId).startsWith("imap:work:");
    assertThat(homeId).startsWith("imap:private:");
    // no account argument: the id says where the mail is
    assertThat(get.invoke(context(false), args("message", workId)))
        .containsEntry("subject", "from work")
        .containsEntry("account", "work");
    assertThat(get.invoke(context(false), args("message", homeId)))
        .containsEntry("subject", "from home")
        .containsEntry("account", "private");
    assertThat(
            (String)
                mark.invoke(context(false), args("message", homeId, "seen", true)).get("message"))
        .startsWith("imap:private:");
    String moved =
        (String)
            move.invoke(context(false), args("message", workId, "folder", "Archive"))
                .get("message");
    assertThat(moved).startsWith("imap:work:");

    // the other account was not touched
    assertThat(items(list.invoke(context(false), args("folder", "Archive", "account", "private"))))
        .isEmpty();
    assertThat(items(list.invoke(context(false), args("folder", "Archive", "account", "work"))))
        .hasSize(1);
    // an id of an account that is not set up
    String ghost = workId.replace("imap:work:", "imap:ghost:");
    assertThatThrownBy(() -> get.invoke(context(false), args("message", ghost)))
        .hasMessageContaining("account 'ghost', which is not set up");
    assertThatThrownBy(() -> get.invoke(context(false), args("message", "file:/etc/passwd")))
        .hasMessageContaining("is not the id of an IMAP mail");
  }

  @Test
  void theAccountWithoutANameKeepsTheIdsItAlwaysHad() throws Exception {
    var list = new ImapRead.Listing();
    list.configure(work.settings());
    list.useDataDirectory(data);
    opened.add(list);

    String id =
        (String) items(list.invoke(context(false), args("folder", "INBOX"))).get(0).get("id");

    assertThat(id).startsWith("imap:").doesNotStartWith("imap:default:");
    assertThat(MessageId.parse(id).orElseThrow().account()).isEqualTo("default");
    assertThat(id.split(":", -1)).hasSize(5); // imap, folder, validity, uid, message-id
  }

  @Test
  void eachAccountHasItsOwnCacheFile() throws Exception {
    var list = make(new ImapRead.Listing());
    list.invoke(context(false), args("folder", "INBOX", "account", "work"));
    list.invoke(context(false), args("folder", "INBOX", "account", "private"));

    try (var files = java.nio.file.Files.list(data)) {
      assertThat(files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".cache.db")))
          .anyMatch(n -> n.startsWith("work."))
          .anyMatch(n -> n.startsWith("private."));
    }
  }

  @Test
  void theSourceWatchesEveryAccountAndEventsNameTheirs() throws Exception {
    List<Event> events = new CopyOnWriteArrayList<>();
    ImapSource source = new ImapSource();
    Map<String, Object> accounts = both();
    for (Object a : accounts.values()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) a;
      m.put("initial", "all");
    }
    source.configure(accounts);
    source.useDataDirectory(data);
    source.start(events::add);
    try {
      long end = System.nanoTime() + 10_000_000_000L;
      while (events.stream().filter(e -> e.type().equals("mail.received")).count() < 2
          && System.nanoTime() < end) {
        Thread.sleep(20);
      }
    } finally {
      source.stop();
    }

    var received = events.stream().filter(e -> e.type().equals("mail.received")).toList();
    assertThat(received)
        .extracting(e -> e.data().get("account"))
        .containsExactlyInAnyOrder("work", "private");
    for (Event e : received) {
      String account = (String) e.data().get("account");
      assertThat((String) e.data().get("message")).startsWith("imap:" + account + ":");
      assertThat(e.data().get("subject"))
          .isEqualTo(account.equals("work") ? "from work" : "from home");
    }
  }

  @Test
  void theAccountsAreListedWithHowTheyAreDoing() throws Exception {
    var accounts = make(new ImapAccounts());
    Map<String, Object> bad =
        home.settings("password_command", List.of("sh", "-c", "printf wrong"));
    Map<String, Object> config = new java.util.LinkedHashMap<>();
    config.put("work", work.settings());
    config.put("private", bad);
    var checked = new ImapAccounts();
    checked.configure(config);
    opened.add(checked);

    var unknown = items(accounts.invoke(context(false), Map.of()));
    assertThat(unknown).extracting(m -> m.get("name")).containsExactly("work", "private");
    assertThat(unknown.get(0)).containsEntry("kind", "imap").containsEntry("folder", "INBOX");
    assertThat((String) unknown.get(0).get("address")).isEqualTo("me@127.0.0.1");

    var after = items(checked.invoke(context(false), args("check", true)));
    assertThat(after.get(0)).containsEntry("state", "ok");
    assertThat(after.get(1)).containsEntry("state", "error");
    assertThat((String) after.get(1).get("error")).isNotEmpty().doesNotContain("wrong");
    assertThat(after.toString()).doesNotContain("secret");
  }

  @Test
  void theSettingsOfSeveralAccountsAreChecked() throws Exception {
    Map<String, Object> mixed = new HashMap<>(work.settings());
    mixed.put("private", home.settings());
    assertThatThrownBy(() -> new ImapRead.Listing().configure(mixed))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining("outside of the accounts");
    Map<String, Object> badName = Map.of("Work Account", work.settings());
    assertThatThrownBy(() -> new ImapRead.Listing().configure(badName))
        .hasMessageContaining("the account name 'Work Account'");
    // a mistake inside an account is the mistake of that account
    Map<String, Object> wrong = new java.util.LinkedHashMap<>();
    wrong.put("work", work.settings("nope", 1L));
    assertThatThrownBy(() -> new ImapRead.Listing().configure(wrong))
        .hasMessageContaining("unknown imap setting 'nope'");
  }
}
