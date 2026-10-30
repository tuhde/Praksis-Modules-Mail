// SPDX-License-Identifier: Apache-2.0
/** An address book: {@code contacts.add}, {@code contacts.search} and {@code contacts.remove}. */
module it.uhde.praksis.module.contacts {
  requires it.uhde.praksis.capability;
  requires jakarta.mail;
  requires java.logging;
  requires java.sql;
  requires org.xerial.sqlitejdbc;

  provides it.uhde.praksis.capability.Capability with
      it.uhde.praksis.module.contacts.ContactsAdd,
      it.uhde.praksis.module.contacts.ContactsRemove,
      it.uhde.praksis.module.contacts.ContactsSearch;
}
