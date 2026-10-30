// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Event;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class CredentialLoginTest {
  private static Map<String, Object> bound(Server server) {
    Map<String, Object> settings = server.settings("credential", "imap:work");
    settings.remove("password_command");
    return settings;
  }

  /** A well-formed id of a mail that is not on the server. */
  private static final String SOME_MAIL =
      new MessageId("INBOX", 1L, 999L, "<x@example.org>").encode();

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

  @Test
  void theSourceAndTheMoveLogInWithTheBoundSecretAndAskByName() throws Exception {
    try (Server server = new Server()) {
      List<String> asked = new CopyOnWriteArrayList<>();
      List<Event> events = new CopyOnWriteArrayList<>();
      ImapSource source = new ImapSource();
      source.configure(bound(server));
      source.useCredentials(
          name -> {
            asked.add("source:" + name);
            return "secret";
          });
      server.deliver(false, "Subject: one", "Message-ID: <1@example.org>");
      source.start(events::add);
      try {
        long end = System.nanoTime() + 10_000_000_000L;
        while (events.isEmpty() && System.nanoTime() < end) {
          Thread.sleep(10);
        }
        assertThat(events).hasSize(1);

        ImapMove move = new ImapMove();
        move.configure(bound(server));
        move.useCredentials(
            name -> {
              asked.add("move:" + name);
              return "secret";
            });
        Map<String, Object> result =
            move.invoke(
                CONTEXT,
                Map.of("message", events.get(0).data().get("message"), "folder", "Archive"));
        assertThat((String) result.get("message")).startsWith("imap:");
        move.close();
      } finally {
        source.stop();
      }
      assertThat(asked).contains("source:imap:work", "move:imap:work");
    }
  }

  @Test
  void aWrongSecretOrAMissingResolverIsAnErrorThatNeverShowsTheSecret() throws Exception {
    try (Server server = new Server()) {
      ImapMove move = new ImapMove();
      move.configure(bound(server));
      Map<String, Object> args = Map.of("message", SOME_MAIL, "folder", "Archive");

      assertThatThrownBy(() -> move.invoke(CONTEXT, args))
          .isInstanceOf(CapabilityException.class)
          .hasMessageContaining("credential 'imap:work' cannot be read");

      move.useCredentials(name -> "hunter2-wrong");
      assertThatThrownBy(() -> move.invoke(CONTEXT, args))
          .isInstanceOf(CapabilityException.class)
          .hasMessageContaining("cannot log in")
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain("hunter2"));

      move.useCredentials(
          name -> {
            throw new CapabilityException("credential '" + name + "' is not bound", false);
          });
      assertThatThrownBy(() -> move.invoke(CONTEXT, args))
          .hasMessageContaining("credential 'imap:work' is not bound");
      move.close();
    }
  }

  @Test
  void theCommandStillWorksWhenNoCredentialIsSet() throws Exception {
    try (Server server = new Server()) {
      ImapMove move = new ImapMove();
      move.configure(server.settings());
      List<String> asked = new ArrayList<>();
      move.useCredentials(
          name -> {
            asked.add(name);
            return "unused";
          });
      server.deliver(false, "Subject: x");
      // the login works through password_command; the resolver is never asked
      assertThatThrownBy(
              () -> move.invoke(CONTEXT, Map.of("message", SOME_MAIL, "folder", "Archive")))
          .hasMessageNotContaining("cannot log in");
      assertThat(asked).isEmpty();
      move.close();
    }
  }
}
