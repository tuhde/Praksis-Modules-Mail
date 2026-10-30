// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import it.uhde.praksis.capability.CapabilityException;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.HeaderTerm;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import org.eclipse.angus.mail.imap.AppendUID;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;

/**
 * {@code mail.append}: puts a mail into a folder, which is made if it does not exist. It is how the
 * copy of a sent mail gets into the Sent folder and how a draft is saved. The mail is read (or
 * unread), and marked as a draft if asked. A mail with a Message-ID that is in the folder already
 * is not put there a second time and the call names the one that is, so that a repeat after a crash
 * does not leave two copies.
 */
public final class ImapAppend extends ImapRead {
  private static final Session SESSION = Session.getInstance(new Properties());

  @Override
  public String name() {
    return "mail.append";
  }

  @Override
  Map<String, Object> read(IMAPStore store, Map<String, Object> args)
      throws MessagingException, IOException, CapabilityException, SQLException {
    if (!(args.get("content") instanceof byte[] content) || content.length == 0) {
      throw new CapabilityException("mail.append needs the content of the mail", false);
    }
    MimeMessage message = new MimeMessage(SESSION, new ByteArrayInputStream(content));
    String[] ids = message.getHeader("Message-ID");
    String messageId = ids == null || ids.length == 0 ? "" : ids[0];
    IMAPFolder folder = (IMAPFolder) store.getFolder((String) args.get("folder"));
    String shown = display(folder);
    if (dryRun) {
      return Map.of("message", new MessageId(account, shown, 0, 0, messageId).encode());
    }
    if (!folder.exists()) {
      folder.create(Folder.HOLDS_MESSAGES);
    } else if (!messageId.isEmpty()) {
      folder.open(Folder.READ_ONLY);
      try {
        Message[] same = folder.search(new HeaderTerm("Message-ID", messageId));
        if (same.length > 0) {
          return Map.of(
              "message",
              new MessageId(
                      account, shown, folder.getUIDValidity(), folder.getUID(same[0]), messageId)
                  .encode());
        }
      } finally {
        folder.close(false);
      }
    }
    Flags flags = new Flags();
    if (!Boolean.FALSE.equals(args.get("seen"))) {
      flags.add(Flags.Flag.SEEN);
    }
    if (Boolean.TRUE.equals(args.get("draft"))) {
      flags.add(Flags.Flag.DRAFT);
    }
    message.setFlags(flags, true);
    AppendUID[] appended = folder.appendUIDMessages(new Message[] {message});
    if (cache != null) {
      cache.stale(shown);
    }
    AppendUID where = appended != null && appended.length > 0 ? appended[0] : null;
    return Map.of(
        "message",
        new MessageId(
                account,
                shown,
                where == null ? 0 : where.uidvalidity,
                where == null ? 0 : where.uid,
                messageId)
            .encode());
  }
}
