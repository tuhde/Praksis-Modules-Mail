// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.smtp;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists the accounts that can send: the name, the address mails go out from, the server, the limit
 * per hour and how many were sent in the last hour. Nothing is changed and no secret is shown.
 */
public final class MailSenders implements Capability {
  private final Map<String, SmtpSettings> accounts = new LinkedHashMap<>();
  private Path directory;

  @Override
  public String name() {
    return "mail.senders";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    accounts.clear();
    accounts.putAll(Accounts.parse(config, SmtpSettings::parse));
  }

  @Override
  public synchronized void useDataDirectory(Path directory) {
    this.directory = directory;
  }

  @Override
  public synchronized Map<String, Object> invoke(
      CapabilityContext context, Map<String, Object> args) {
    List<Object> items = new ArrayList<>();
    for (var entry : accounts.entrySet()) {
      SmtpSettings s = entry.getValue();
      long sent = -1;
      if (directory != null) {
        try (Outbox outbox =
            Outbox.open(
                directory,
                (Accounts.DEFAULT.equals(entry.getKey()) ? "" : entry.getKey() + ".")
                    + s.account())) {
          sent = outbox.lastHour(System.currentTimeMillis());
        } catch (SQLException e) {
          // not known
        }
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("name", entry.getKey());
      item.put("from", s.from().toString());
      item.put("server", s.host() + ":" + s.port());
      item.put("security", s.security().name().toLowerCase());
      item.put("max_per_hour", (long) s.maxPerHour());
      item.put("sent_last_hour", sent);
      items.add(item);
    }
    return Map.of("items", items);
  }
}
