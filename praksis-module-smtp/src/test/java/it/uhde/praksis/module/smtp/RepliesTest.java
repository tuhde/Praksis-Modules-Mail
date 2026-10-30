// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.smtp;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.Event;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RepliesTest {
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

  private MailReply reply;
  private MailForward forward;

  @BeforeEach
  void setUp() throws Exception {
    Map<String, Object> settings = new HashMap<>();
    settings.put("host", "smtp.example.org");
    settings.put("from", "Me <me@example.org>");
    settings.put("allowed_from", List.of("alias@example.org"));
    settings.put("user", "me");
    settings.put("credential", "smtp:me");
    reply = new MailReply();
    reply.configure(settings);
    forward = new MailForward();
    forward.configure(settings);
  }

  private static Map<String, Object> mail(Object... kv) {
    Map<String, Object> m = new HashMap<>();
    m.put("from", "Anna <anna@example.org>");
    m.put("to", "Me <me@example.org>");
    m.put("subject", "Lunch?");
    m.put("date", "Tue, 27 Oct 2026 10:00:00 +0100");
    m.put("message_id", "<1@example.org>");
    m.put("text", "Are you free\ntomorrow?");
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  private Map<String, Object> reply(Map<String, Object> mail, boolean all) throws Exception {
    Map<String, Object> args = new HashMap<>(mail);
    args.put("all", all);
    return reply.invoke(CONTEXT, args);
  }

  @Test
  void aReplyGoesToTheSenderWithOneRePrefixTheThreadAndTheOriginalQuoted() throws Exception {
    Map<String, Object> r = reply(mail("signature", "Me\nhttps://me.example"), false);

    assertThat(r).containsEntry("to", "Anna <anna@example.org>").containsEntry("cc", "");
    assertThat(r).containsEntry("subject", "Re: Lunch?");
    assertThat(r)
        .containsEntry("in_reply_to", "<1@example.org>")
        .containsEntry("references", "<1@example.org>");
    // room to write, the signature, then the quoted mail
    assertThat((String) r.get("text"))
        .isEqualTo(
            "\n\n-- \nMe\nhttps://me.example\n\n"
                + "On Tue, 27 Oct 2026 10:00:00 +0100, Anna <anna@example.org> wrote:\n"
                + "> Are you free\n> tomorrow?\n");
  }

  @Test
  void theReplyToHeaderWinsAndTheChainOfReferencesGrows() throws Exception {
    Map<String, Object> r =
        reply(
            mail("reply_to", "Anna Work <anna@work.example>", "references", "<a@x> <b@x>"), false);
    assertThat(r)
        .containsEntry("to", "Anna Work <anna@work.example>")
        .containsEntry("references", "<a@x> <b@x> <1@example.org>");

    // a long thread keeps the first id and the newest ones
    StringBuilder ids = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      ids.append("<m").append(i).append("@x> ");
    }
    String chain =
        (String) reply(mail("references", ids.toString().strip()), false).get("references");
    String[] kept = chain.split(" ");
    assertThat(kept).hasSize(20);
    assertThat(kept[0]).isEqualTo("<m0@x>");
    assertThat(kept[kept.length - 1]).isEqualTo("<1@example.org>");
    assertThat(kept[kept.length - 2]).isEqualTo("<m39@x>");
  }

  @Test
  void subjectsGetOneRePrefixHoweverManyThereWere() throws Exception {
    for (String subject :
        List.of(
            "Lunch?",
            "Re: Lunch?",
            "RE: re: Lunch?",
            "AW: Re: Lunch?",
            "Re[2]: Lunch?",
            "  re :Lunch?")) {
      assertThat(reply(mail("subject", subject), false))
          .as(subject)
          .containsEntry("subject", "Re: Lunch?");
    }
    assertThat(reply(mail("subject", ""), false)).containsEntry("subject", "Re:");
  }

  @Test
  void replyAllAnswersEveryoneExceptMeAndNobodyTwice() throws Exception {
    Map<String, Object> r =
        reply(
            mail(
                "to", "Me <me@example.org>, Bob <bob@example.org>, ALIAS@example.org",
                "cc", "carl@example.org, bob@example.org, Anna <ANNA@example.org>"),
            true);

    assertThat(r).containsEntry("to", "Anna <anna@example.org>, Bob <bob@example.org>");
    assertThat(r).containsEntry("cc", "carl@example.org");

    // a reply to the sender alone does not copy the others
    Map<String, Object> one =
        reply(mail("to", "Me <me@example.org>, bob@example.org", "cc", "carl@example.org"), false);
    assertThat(one).containsEntry("to", "Anna <anna@example.org>").containsEntry("cc", "");
  }

  @Test
  void aReplyToAMailIWroteGoesToThePeopleIWroteTo() throws Exception {
    Map<String, Object> r =
        reply(mail("from", "Me <me@example.org>", "to", "Anna <anna@example.org>"), false);
    assertThat(r).containsEntry("to", "Anna <anna@example.org>");
  }

  @Test
  void quotingKeepsBlankLinesNestsQuotesAndIsBounded() throws Exception {
    assertThat(Replies.quote("a\n\nb")).isEqualTo("> a\n> \n> b");
    assertThat(Replies.quote("> older\nnew")).isEqualTo(">> older\n> new");
    assertThat(Replies.quote("a\r\nb\r\n\r\n")).isEqualTo("> a\n> b");
    assertThat(Replies.quote("")).isEmpty();
    String big = "x".repeat(300_000);
    assertThat(Replies.quote(big).length()).isLessThan(100_100);
    // missing fields are no reason to fail
    Map<String, Object> bare = reply.invoke(CONTEXT, Map.of());
    assertThat(bare).containsEntry("to", "").containsEntry("subject", "Re:");
    assertThat((String) bare.get("text")).contains("the sender wrote:");
    // an address header that is garbage gives no recipients
    assertThat(reply(mail("from", "<<<>>>", "to", ""), false)).containsEntry("to", "");
  }

  @Test
  void aForwardKeepsTheOriginalWithItsHeadersAndStartsNoThread() throws Exception {
    Map<String, Object> args =
        mail("subject", "Fwd: AW: Lunch?", "cc", "bob@example.org", "signature", "Me");

    Map<String, Object> f = forward.invoke(CONTEXT, args);

    assertThat(f).containsEntry("subject", "Fwd: AW: Lunch?").containsEntry("to", "");
    assertThat(f).containsEntry("in_reply_to", "").containsEntry("references", "");
    assertThat((String) f.get("text"))
        .isEqualTo(
            "\n\n-- \nMe\n\n---------- Forwarded message ----------\n"
                + "From: Anna <anna@example.org>\n"
                + "Date: Tue, 27 Oct 2026 10:00:00 +0100\n"
                + "Subject: Fwd: AW: Lunch?\n"
                + "To: Me <me@example.org>\n"
                + "Cc: bob@example.org\n\n"
                + "Are you free\ntomorrow?\n");
    // one Fwd: however many there were
    assertThat(forward.invoke(CONTEXT, mail("subject", "Fwd: FW: Lunch?")))
        .containsEntry("subject", "Fwd: Lunch?");
    assertThat(forward.invoke(CONTEXT, mail("subject", ""))).containsEntry("subject", "Fwd:");
  }
}
