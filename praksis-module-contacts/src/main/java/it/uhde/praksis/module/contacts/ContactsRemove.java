// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.contacts;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.sql.SQLException;
import java.util.Map;

/**
 * Forgets one address. It would be added again the next time it is seen, so this is for an address
 * that a person does not want to be offered. A destructive capability: it needs a grant of its own.
 */
public final class ContactsRemove extends Base {
  @Override
  public String name() {
    return "contacts.remove";
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    String address = args.get("address") instanceof String s ? s : "";
    try {
      return Map.of("removed", !context.dryRun() && book().remove(address));
    } catch (SQLException e) {
      throw new CapabilityException("address book: " + e.getMessage(), true, e);
    }
  }
}
