// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Credentials;
import it.uhde.praksis.capability.EventSink;
import it.uhde.praksis.capability.EventSource;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeUtility;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the mails of the folder of every account into {@code mail.received} events, and tells when
 * something in it changed ({@code mail.changed}). Each account is watched on its own, by an {@link
 * AccountWatch}; an event names its account in {@code account} and in the id of the mail.
 */
public final class ImapSource implements EventSource {
  private final Map<String, AccountWatch> watches = new LinkedHashMap<>();

  @Override
  public String name() {
    return "imap.inbox";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    watches.clear();
    Accounts.parse(config, ImapSettings::parse)
        .forEach((name, settings) -> watches.put(name, new AccountWatch(name, settings)));
  }

  @Override
  public synchronized void useCredentials(Credentials credentials) {
    watches.values().forEach(w -> w.useCredentials(credentials));
  }

  @Override
  public synchronized void useDataDirectory(java.nio.file.Path directory) {
    watches.values().forEach(w -> w.useDataDirectory(directory));
  }

  @Override
  public synchronized void start(EventSink sink) throws CapabilityException {
    if (watches.isEmpty()) {
      throw new CapabilityException("imap source is not configured", false);
    }
    List<AccountWatch> started = new ArrayList<>();
    try {
      for (AccountWatch w : watches.values()) {
        w.start(sink);
        started.add(w);
      }
    } catch (CapabilityException | RuntimeException e) {
      started.forEach(AccountWatch::stop);
      throw e;
    }
  }

  @Override
  public synchronized void stop() {
    watches.values().forEach(AccountWatch::stop);
  }

  /** The first value of a header, unfolded and with encoded words decoded; empty if absent. */
  static String header(Message message, String name) throws MessagingException {
    String[] values = message.getHeader(name);
    if (values == null || values.length == 0) {
      return "";
    }
    String text = values[0].replaceAll("\\s*\\r?\\n\\s*", " ").strip();
    try {
      return MimeUtility.decodeText(text);
    } catch (UnsupportedEncodingException e) {
      return text;
    }
  }
}
