// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.contacts;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.sql.SQLException;
import java.util.Map;

/**
 * Counts the addresses of a header-style list ({@code Name <a@b>, c@d}) as seen now. A new address
 * is added, a known one counts one more and keeps the newest name. Addresses that are no person
 * (no-reply, bounces) and text that is no address are left out, so a rule can hand over the header
 * of a mail as it is.
 */
public final class ContactsAdd extends Base {
  @Override
  public String name() {
    return "contacts.add";
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    String addresses = args.get("addresses") instanceof String s ? s : "";
    if (context.dryRun()) {
      return Map.of("seen", (long) Book.parse(addresses).size(), "added", 0L);
    }
    try {
      int[] counted = book().add(addresses, System.currentTimeMillis());
      return Map.of("seen", (long) counted[0], "added", (long) counted[1]);
    } catch (SQLException e) {
      throw new CapabilityException("address book: " + e.getMessage(), true, e);
    }
  }
}
