// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * The opaque {@code message} id of a mail on the server: the folder, the UID validity and the UID
 * (which together name a mail for as long as the server keeps them) and the Message-ID header,
 * which finds the mail again after it moved to another folder.
 *
 * @param account the account the mail is in; the one without a name is {@code default}
 * @param folder the folder the mail was in
 * @param validity the UID validity of that folder
 * @param uid the UID of the mail in it, or 0 if unknown
 * @param messageId the Message-ID header, or empty
 */
record MessageId(String account, String folder, long validity, long uid, String messageId) {
  private static final String PREFIX = "imap:";

  /** A mail of the account without a name. */
  MessageId(String folder, long validity, long uid, String messageId) {
    this(Accounts.DEFAULT, folder, validity, uid, messageId);
  }

  /** The id of a mail of the account without a name is as it always was; others name theirs. */
  String encode() {
    String rest = b64(folder) + ":" + validity + ":" + uid + ":" + b64(messageId);
    return Accounts.DEFAULT.equals(account) ? PREFIX + rest : PREFIX + account + ":" + rest;
  }

  static Optional<MessageId> parse(String text) {
    if (text == null || !text.startsWith(PREFIX)) {
      return Optional.empty();
    }
    String[] parts = text.substring(PREFIX.length()).split(":", -1);
    if (parts.length != 4 && parts.length != 5) {
      return Optional.empty();
    }
    int o = parts.length - 4; // 1 if the account is named
    try {
      return Optional.of(
          new MessageId(
              o == 1 ? parts[0] : Accounts.DEFAULT,
              unb64(parts[o]),
              Long.parseLong(parts[o + 1]),
              Long.parseLong(parts[o + 2]),
              unb64(parts[o + 3])));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static String b64(String s) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  private static String unb64(String s) {
    return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
  }
}
