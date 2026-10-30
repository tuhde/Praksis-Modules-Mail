// SPDX-License-Identifier: Apache-2.0
/** Sends mail through an SMTP server: {@code mail.send}, and {@code mail.compose} for drafts. */
module it.uhde.praksis.module.smtp {
  requires it.uhde.praksis.capability;
  requires jakarta.mail;
  requires org.eclipse.angus.mail;
  requires java.logging;
  requires java.sql;
  requires org.xerial.sqlitejdbc;

  provides it.uhde.praksis.capability.Capability with
      it.uhde.praksis.module.smtp.MailCompose,
      it.uhde.praksis.module.smtp.MailForward,
      it.uhde.praksis.module.smtp.MailReply,
      it.uhde.praksis.module.smtp.MailSenders,
      it.uhde.praksis.module.smtp.MailSend;
}
