// SPDX-License-Identifier: Apache-2.0
/**
 * Reads mail from an IMAP server: the {@code mail.received} event, {@code mail.move}, and the
 * reading capabilities {@code mail.folders}, {@code mail.list} and {@code mail.get}.
 */
module it.uhde.praksis.module.imap {
  requires it.uhde.praksis.capability;
  requires it.uhde.praksis.mail.html;
  requires jakarta.mail;
  requires org.eclipse.angus.mail;
  requires java.logging;
  requires java.sql;
  requires org.xerial.sqlitejdbc;

  provides it.uhde.praksis.capability.Capability with
      it.uhde.praksis.module.imap.ImapAccounts,
      it.uhde.praksis.module.imap.ImapAppend,
      it.uhde.praksis.module.imap.ImapEdit.Delete,
      it.uhde.praksis.module.imap.ImapEdit.Mark,
      it.uhde.praksis.module.imap.ImapMove,
      it.uhde.praksis.module.imap.ImapRead.Attachment,
      it.uhde.praksis.module.imap.ImapRead.Folders,
      it.uhde.praksis.module.imap.ImapRead.Get,
      it.uhde.praksis.module.imap.ImapRead.Listing,
      it.uhde.praksis.module.imap.ImapRead.Search;
  provides it.uhde.praksis.capability.EventSource with
      it.uhde.praksis.module.imap.ImapSource;
}
