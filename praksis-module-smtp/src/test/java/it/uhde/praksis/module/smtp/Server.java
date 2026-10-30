// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.smtp;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** An SMTP server in the test process with one account that may log in, and its settings. */
final class Server implements AutoCloseable {
  final GreenMail greenMail = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp").dynamicPort());

  Server() {
    greenMail.start();
    greenMail.setUser("me@example.org", "me", "secret");
    greenMail.setUser("anna@example.org", "anna", "x");
    greenMail.setUser("bob@example.org", "bob", "x");
    greenMail.setUser("carl@example.org", "carl", "x");
  }

  /** Settings for the capabilities; {@code extra} are key, value pairs that add or replace. */
  Map<String, Object> settings(Object... extra) {
    Map<String, Object> m = new HashMap<>();
    m.put("host", "127.0.0.1");
    m.put("port", (long) greenMail.getSmtp().getPort());
    m.put("security", "none");
    m.put("user", "me");
    m.put("password_command", List.of("sh", "-c", "printf secret"));
    m.put("from", "Me <me@example.org>");
    for (int i = 0; i < extra.length; i += 2) {
      if (extra[i + 1] == null) {
        m.remove((String) extra[i]);
      } else {
        m.put((String) extra[i], extra[i + 1]);
      }
    }
    return m;
  }

  MimeMessage[] received() {
    return greenMail.getReceivedMessages();
  }

  @Override
  public void close() {
    greenMail.stop();
  }
}
