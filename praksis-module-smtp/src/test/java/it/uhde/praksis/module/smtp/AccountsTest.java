// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.smtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Event;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Several accounts in one module: [module.smtp.work] and [module.smtp.private]. */
class AccountsTest {
  private static CapabilityContext context(String key) {
    return new CapabilityContext() {
      @Override
      public boolean dryRun() {
        return false;
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

  private Server work;
  private Server home;
  private final List<Capability> opened = new ArrayList<>();
  private int calls;

  @BeforeEach
  void setUp() {
    work = new Server();
    home = new Server();
  }

  @AfterEach
  void tearDown() {
    opened.forEach(Capability::close);
    work.close();
    home.close();
  }

  private Map<String, Object> both(Object... limit) {
    Map<String, Object> workSettings =
        work.settings(
            "from", "Me at Work <me@work.example>", "allowed_from", List.of("alias@work.example"));
    if (limit.length > 0) {
      workSettings.put("max_per_hour", limit[0]);
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("work", workSettings);
    m.put("private", home.settings("from", "Me <me@home.example>"));
    return m;
  }

  private <T extends Capability> T make(T capability, Map<String, Object> config) throws Exception {
    capability.configure(config);
    capability.useDataDirectory(data);
    capability.useUploadsDirectory(Files.createDirectories(data.resolve("up")));
    opened.add(capability);
    return capability;
  }

  private Map<String, Object> send(Capability c, Object... kv) throws Exception {
    Map<String, Object> args =
        new HashMap<>(Map.of("to", "anna@example.org", "subject", "s", "text", "t"));
    for (int i = 0; i < kv.length; i += 2) {
      args.put((String) kv[i], kv[i + 1]);
    }
    return c.invoke(context("key-" + calls++), args);
  }

  @Test
  void aMailGoesOutThroughTheAccountThatIsNamedAndNeverGuesses() throws Exception {
    var send = make(new MailSend(), both());

    assertThatThrownBy(() -> send(send))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining("there are several accounts");
    assertThatThrownBy(() -> send(send, "account", "nope"))
        .hasMessageContaining("there is no account 'nope'");
    assertThat(work.received()).isEmpty();
    assertThat(home.received()).isEmpty();

    send(send, "account", "work");
    send(send, "account", "private");

    assertThat(work.received()).hasSize(1);
    assertThat(work.received()[0].getFrom()[0].toString()).contains("me@work.example");
    assertThat(home.received()).hasSize(1);
    assertThat(home.received()[0].getFrom()[0].toString()).contains("me@home.example");
    // an address of another account is not one of this account\'s
    assertThatThrownBy(() -> send(send, "account", "work", "from", "me@home.example"))
        .hasMessageContaining("may not send as me@home.example");
    send(send, "account", "work", "from", "alias@work.example");
  }

  @Test
  void eachAccountHasALimitAndAnOutboxOfItsOwn() throws Exception {
    var send = make(new MailSend(), both(1L));

    send(send, "account", "work");
    assertThatThrownBy(() -> send(send, "account", "work"))
        .hasMessageContaining("limit of 1 mails per hour");
    // the other account is not held up by it
    send(send, "account", "private");
    send(send, "account", "private");

    try (var files = Files.list(data)) {
      assertThat(files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".outbox.db")))
          .anyMatch(n -> n.startsWith("work."))
          .anyMatch(n -> n.startsWith("private."));
    }
  }

  @Test
  void composeAndAnswersUseTheAccountsOwnAddresses() throws Exception {
    var compose = make(new MailCompose(), both());
    var reply = make(new MailReply(), both());

    Map<String, Object> draft =
        compose.invoke(
            context("c"), new HashMap<>(Map.of("account", "private", "subject", "x", "text", "y")));
    assertThat(new String((byte[]) draft.get("raw"))).contains("me@home.example");
    assertThatThrownBy(() -> compose.invoke(context("c"), Map.of("subject", "x")))
        .hasMessageContaining("there are several accounts");

    // the addresses of the account are left out of an answer to all, those of the other are not
    Map<String, Object> mail =
        new HashMap<>(
            Map.of(
                "from",
                "Anna <anna@example.org>",
                "to",
                "me@work.example, me@home.example",
                "subject",
                "s",
                "message_id",
                "<1@x>",
                "text",
                "t",
                "all",
                true));
    mail.put("account", "work");
    assertThat(reply.invoke(context("r"), mail).get("to").toString())
        .contains("anna@example.org")
        .contains("me@home.example")
        .doesNotContain("me@work.example");
  }

  @Test
  void theSendersAreListedWithTheirLimitAndWhatWasSent() throws Exception {
    var send = make(new MailSend(), both(5L));
    var senders = make(new MailSenders(), both(5L));
    send(send, "account", "work");

    @SuppressWarnings("unchecked")
    var items = (List<Map<String, Object>>) senders.invoke(context("s"), Map.of()).get("items");

    assertThat(items).extracting(m -> m.get("name")).containsExactly("work", "private");
    assertThat(items.get(0)).containsEntry("max_per_hour", 5L).containsEntry("sent_last_hour", 1L);
    assertThat(items.get(1)).containsEntry("sent_last_hour", 0L);
    assertThat(items.get(0).get("from").toString()).contains("me@work.example");
    assertThat(items.toString()).doesNotContain("secret");
  }

  @Test
  void theAccountWithoutANameStillWorksAsBefore() throws Exception {
    var send = make(new MailSend(), work.settings());
    send(send); // no account argument needed
    assertThat(work.received()).hasSize(1);
    try (var files = Files.list(data)) {
      assertThat(files.map(f -> f.getFileName().toString())).contains("me@127.0.0.1.outbox.db");
    }
  }

  @Test
  void theSettingsAreChecked() {
    Map<String, Object> mixed = new HashMap<>(work.settings());
    mixed.put("private", home.settings());
    assertThatThrownBy(() -> new MailSend().configure(mixed))
        .hasMessageContaining("outside of the accounts");
    assertThatThrownBy(() -> new MailSend().configure(Map.of("Bad Name", work.settings())))
        .hasMessageContaining("the account name 'Bad Name'");
  }
}
