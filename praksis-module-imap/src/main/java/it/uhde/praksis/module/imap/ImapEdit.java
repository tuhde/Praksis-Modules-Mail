// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import it.uhde.praksis.capability.CapabilityException;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import java.util.Map;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;

/**
 * The two capabilities that change a mail where it is: {@code mail.mark} (read, unread, starred)
 * and {@code mail.delete} (gone for good). They share the connection handling of {@link ImapRead}.
 */
public abstract class ImapEdit extends ImapRead {
  @Override
  final Map<String, Object> read(IMAPStore store, Map<String, Object> args)
      throws MessagingException, CapabilityException {
    MessageId id = id(args);
    IMAPFolder folder = (IMAPFolder) store.getFolder(id.folder());
    folder.open(Folder.READ_WRITE);
    try {
      Message message =
          folder.getUIDValidity() == id.validity() ? folder.getMessageByUID(id.uid()) : null;
      return edit(store, folder, message, id, args);
    } finally {
      try {
        folder.close(false);
      } catch (MessagingException e) {
        close(); // the next call opens a new connection
      }
    }
  }

  abstract Map<String, Object> edit(
      IMAPStore store, IMAPFolder folder, Message message, MessageId id, Map<String, Object> args)
      throws MessagingException, CapabilityException;

  /** {@code mail.mark}. The UID does not change, so the answer names the same mail. */
  public static final class Mark extends ImapEdit {
    @Override
    public String name() {
      return "mail.mark";
    }

    @Override
    Map<String, Object> edit(
        IMAPStore store, IMAPFolder folder, Message message, MessageId id, Map<String, Object> args)
        throws MessagingException, CapabilityException {
      if (message == null) {
        throw new CapabilityException("mail not found: it is not in " + id.folder(), false);
      }
      if (!dryRun) {
        Boolean seen = args.get("seen") instanceof Boolean on ? on : null;
        Boolean flagged = args.get("flagged") instanceof Boolean on ? on : null;
        if (seen != null) {
          message.setFlag(Flags.Flag.SEEN, seen);
        }
        if (flagged != null) {
          message.setFlag(Flags.Flag.FLAGGED, flagged);
        }
        if (cache != null) {
          cache.flags(id.folder(), id.uid(), seen, flagged);
        }
      }
      return Map.of("message", id.encode());
    }
  }

  /**
   * {@code mail.delete}: marks the mail deleted and removes it with {@code UID EXPUNGE}, which
   * touches no other mail. A server without UIDPLUS is refused rather than risk expunging other
   * mails that are marked deleted. A mail that is gone already is not an error.
   */
  public static final class Delete extends ImapEdit {
    @Override
    public String name() {
      return "mail.delete";
    }

    @Override
    Map<String, Object> edit(
        IMAPStore store, IMAPFolder folder, Message message, MessageId id, Map<String, Object> args)
        throws MessagingException, CapabilityException {
      if (message == null) {
        return Map.of("deleted", false);
      }
      if (!store.hasCapability("UIDPLUS")) {
        throw new CapabilityException(
            "the server does not support UIDPLUS, so a mail cannot be deleted safely", false);
      }
      if (!dryRun) {
        message.setFlag(Flags.Flag.DELETED, true);
        folder.expunge(new Message[] {message});
        if (cache != null) {
          cache.remove(id.folder(), id.uid());
        }
      }
      return Map.of("deleted", true);
    }
  }
}
