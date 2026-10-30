// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.smtp;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.util.Map;

/**
 * Makes the starting point of an answer to a mail: who to write to (not yourself, nobody twice),
 * the subject with one "Re:", the threading headers, and the text with the mail quoted and the
 * signature. Only computes; nothing is read or sent. {@code all} answers everyone.
 */
public final class MailReply implements Capability {
  private final Map<String, Replies> accounts = new java.util.LinkedHashMap<>();

  @Override
  public String name() {
    return "mail.reply";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    accounts.clear();
    Accounts.parse(config, SmtpSettings::parse)
        .forEach((name, s) -> accounts.put(name, new Replies(s)));
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    if (accounts.isEmpty()) {
      throw new CapabilityException("mail.reply is not configured", false);
    }
    Replies replies = accounts.get(Accounts.choose(accounts, args.get("account")));
    return replies.reply(args, Boolean.TRUE.equals(args.get("all")));
  }
}
