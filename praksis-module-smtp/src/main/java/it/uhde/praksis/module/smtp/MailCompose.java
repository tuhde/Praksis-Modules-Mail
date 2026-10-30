// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.smtp;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a mail and returns it without sending it: the message id, the mail as it goes over the
 * wire and the number of recipients. It makes the same checks as {@code mail.send}. It is how a
 * draft is made: the result is appended to the Drafts folder.
 */
public final class MailCompose implements Capability {
  private final Map<String, Composer> composers = new java.util.LinkedHashMap<>();

  @Override
  public void useUploadsDirectory(java.nio.file.Path directory) {
    composers.values().forEach(c -> c.uploads(directory));
  }

  @Override
  public String name() {
    return "mail.compose";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    composers.clear();
    Accounts.parse(config, SmtpSettings::parse)
        .forEach((name, s) -> composers.put(name, new Composer(s)));
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    if (composers.isEmpty()) {
      throw new CapabilityException("mail.compose is not configured", false);
    }
    Composer.Composed mail =
        composers.get(Accounts.choose(composers, args.get("account"))).compose(args, false);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("message_id", mail.messageId());
    out.put("raw", mail.raw());
    out.put("recipients", (long) mail.recipients().size());
    return out;
  }
}
