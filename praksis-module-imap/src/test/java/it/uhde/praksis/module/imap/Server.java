// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Flags;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** An IMAP server in the test process with one account, and the settings to reach it. */
final class Server implements AutoCloseable {
  final GreenMail greenMail = new GreenMail(new ServerSetup(0, "127.0.0.1", "imap").dynamicPort());
  private final GreenMailUser user;

  Server() {
    greenMail.start();
    user = greenMail.setUser("me@example.org", "me", "secret");
  }

  /** Settings for the modules; {@code extra} are key, value pairs that add or replace. */
  Map<String, Object> settings(Object... extra) {
    Map<String, Object> m = new java.util.HashMap<>();
    m.put("host", "127.0.0.1");
    m.put("port", (long) greenMail.getImap().getPort());
    m.put("security", "none");
    m.put("user", "me");
    m.put("password_command", List.of("sh", "-c", "printf secret"));
    m.put("poll_interval_ms", 50L);
    for (int i = 0; i < extra.length; i += 2) {
      m.put((String) extra[i], extra[i + 1]);
    }
    return m;
  }

  /** Delivers a mail with exactly these header lines to the INBOX. */
  void deliver(boolean seen, String... headers) throws Exception {
    String text = String.join("\r\n", headers) + "\r\n\r\nbody\r\n";
    MimeMessage message =
        new MimeMessage(
            com.icegreen.greenmail.util.GreenMailUtil.getSession(
                greenMail.getImap().getServerSetup()),
            new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    message.setFlag(Flags.Flag.SEEN, seen);
    user.deliver(message);
  }

  /** Delivers a mail as written (CRLF line ends are made from LF). */
  void deliverRaw(boolean seen, String raw) throws Exception {
    MimeMessage message =
        new MimeMessage(
            com.icegreen.greenmail.util.GreenMailUtil.getSession(
                greenMail.getImap().getServerSetup()),
            new ByteArrayInputStream(raw.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)));
    message.setFlag(Flags.Flag.SEEN, seen);
    user.deliver(message);
  }

  /** Makes an empty folder. */
  void createFolder(String name) throws Exception {
    greenMail.getManagers().getImapHostManager().createMailbox(user, name);
  }

  @Override
  public void close() {
    greenMail.stop();
  }
}
