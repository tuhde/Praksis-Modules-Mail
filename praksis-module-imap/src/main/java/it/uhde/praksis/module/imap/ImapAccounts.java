// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Credentials;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists the accounts of the module: the name, the login and server, the folder that is watched, and
 * how the last attempt to reach the server went. With {@code check} it tries to log in now, with
 * the password the account is set up with, so that a person who has just set an account up can see
 * whether it works. Nothing is changed and no secret is shown.
 */
public final class ImapAccounts implements Capability {
  private final Map<String, ImapSettings> settings = new LinkedHashMap<>();
  private final Map<String, Connection> connections = new LinkedHashMap<>();

  @Override
  public String name() {
    return "mail.accounts";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    settings.clear();
    connections.clear();
    Accounts.parse(config, ImapSettings::parse)
        .forEach(
            (name, s) -> {
              settings.put(name, s);
              connections.put(name, new Connection(s, name));
            });
  }

  @Override
  public synchronized void useCredentials(Credentials credentials) {
    connections.values().forEach(c -> c.useCredentials(credentials));
  }

  @Override
  public synchronized void close() {
    connections.values().forEach(Connection::close);
  }

  @Override
  public synchronized Map<String, Object> invoke(
      CapabilityContext context, Map<String, Object> args) throws CapabilityException {
    boolean check = Boolean.TRUE.equals(args.get("check")) && !context.dryRun();
    List<Object> items = new ArrayList<>();
    for (var entry : settings.entrySet()) {
      if (check) {
        try {
          connections.get(entry.getKey()).store(); // reports how it went
        } catch (Exception e) {
          // the report says it
        }
        connections.get(entry.getKey()).close();
      }
      ImapSettings s = entry.getValue();
      Accounts.Status status = Accounts.status(entry.getKey());
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("name", entry.getKey());
      item.put("kind", "imap");
      item.put("address", s.account());
      item.put("folder", s.folder());
      item.put("state", status == null ? "unknown" : status.ok() ? "ok" : "error");
      item.put("error", status == null ? "" : status.error());
      item.put("checked", status == null ? "" : status.at().toString());
      items.add(item);
    }
    return Map.of("items", items);
  }
}
