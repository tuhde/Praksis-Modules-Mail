// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.contacts;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Finds the contacts that match what a person has typed: every word of the query must start a word
 * of the name or the address. The ones written to most and most recently come first. Each answer
 * has {@code address}, {@code name}, {@code display} (what to put into an address field), {@code
 * seen} and {@code last}. Only reads.
 */
public final class ContactsSearch extends Base {
  private static final int DEFAULT_LIMIT = 10;
  private static final int MAX_LIMIT = 50;

  @Override
  public String name() {
    return "contacts.search";
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    String query = args.get("query") instanceof String s ? s : "";
    long asked = args.get("limit") instanceof Number n ? n.longValue() : DEFAULT_LIMIT;
    int limit = (int) Math.max(1, Math.min(asked, MAX_LIMIT));
    try {
      return Map.of("items", List.copyOf(book().search(query, limit)));
    } catch (SQLException e) {
      throw new CapabilityException("address book: " + e.getMessage(), true, e);
    }
  }
}
