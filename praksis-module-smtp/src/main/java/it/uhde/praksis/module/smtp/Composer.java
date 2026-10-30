// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.smtp;

import it.uhde.praksis.capability.CapabilityException;
import jakarta.activation.DataHandler;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds a message from the arguments of a call. This is where the checks on what is sent are: no
 * header can be given a line break (header injection), every address is a single valid address, the
 * sender is the configured one or one of the allowed ones, the number of recipients and the size of
 * the mail are limited. The result is the mail as it goes over the wire, without a Bcc header.
 */
final class Composer {
  /** A composed mail. */
  record Composed(String messageId, byte[] raw, List<InternetAddress> recipients) {}

  private static final Session SESSION = Session.getInstance(new java.util.Properties());

  private static final java.util.regex.Pattern TOKEN =
      java.util.regex.Pattern.compile(
          "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private final SmtpSettings settings;
  private volatile java.nio.file.Path uploads;

  Composer(SmtpSettings settings) {
    this.settings = settings;
  }

  /** Where the files are that a person chose to attach; they are read by token only. */
  void uploads(java.nio.file.Path directory) {
    this.uploads = directory;
  }

  /**
   * @param strict whether the mail must be sendable: it needs a recipient and a body. A draft need
   *     not.
   */
  Composed compose(Map<String, Object> args, boolean strict) throws CapabilityException {
    InternetAddress from = settings.from();
    if (args.get("from") instanceof String f && !f.isBlank()) {
      from = SmtpSettings.address(f, "from");
      if (!settings.mayUse(from)) {
        throw new CapabilityException(
            "this account may not send as " + from.getAddress() + " (see allowed_from)", false);
      }
    }
    List<InternetAddress> to = addresses(args, "to");
    List<InternetAddress> cc = addresses(args, "cc");
    List<InternetAddress> bcc = addresses(args, "bcc");
    List<InternetAddress> all = new ArrayList<>(to);
    all.addAll(cc);
    all.addAll(bcc);
    if (strict && all.isEmpty()) {
      throw new CapabilityException("a mail needs at least one recipient in to, cc or bcc", false);
    }
    if (all.size() > settings.maxRecipients()) {
      throw new CapabilityException(
          "a mail may have at most "
              + settings.maxRecipients()
              + " recipients, this has "
              + all.size(),
          false);
    }
    String text = string(args, "text");
    String html = string(args, "html");
    if (strict && text.isEmpty() && html.isEmpty()) {
      throw new CapabilityException("a mail needs a text or an html body", false);
    }
    String subject = line(args, "subject");
    String messageId = "<" + UUID.randomUUID() + "@" + domain(from) + ">";
    try {
      MimeMessage message =
          new MimeMessage(SESSION) {
            @Override
            protected void updateMessageID() throws MessagingException {
              setHeader("Message-ID", messageId);
            }
          };
      message.setFrom(from);
      message.setRecipients(
          jakarta.mail.Message.RecipientType.TO, to.toArray(new InternetAddress[0]));
      message.setRecipients(
          jakarta.mail.Message.RecipientType.CC, cc.toArray(new InternetAddress[0]));
      message.setSubject(subject, "UTF-8");
      message.setSentDate(new Date());
      String inReplyTo = line(args, "in_reply_to");
      if (!inReplyTo.isEmpty()) {
        message.setHeader("In-Reply-To", inReplyTo);
      }
      String references = line(args, "references");
      if (!references.isEmpty()) {
        message.setHeader("References", references);
      }
      message.setHeader("X-Mailer", "Praksis");
      List<MimeBodyPart> attachments = attachments(args, uploads, settings.maxBytes());
      MimeMultipart body = bodyOf(text, html);
      if (attachments.isEmpty()) {
        if (html.isEmpty()) {
          message.setText(text, "UTF-8");
        } else {
          message.setContent(body);
        }
      } else {
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart main = new MimeBodyPart();
        if (html.isEmpty()) {
          main.setText(text, "UTF-8");
        } else {
          main.setContent(body);
        }
        mixed.addBodyPart(main);
        for (MimeBodyPart a : attachments) {
          mixed.addBodyPart(a);
        }
        message.setContent(mixed);
      }
      message.saveChanges();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      message.writeTo(out);
      if (out.size() > settings.maxBytes()) {
        throw new CapabilityException(
            "the mail is " + out.size() + " bytes, more than the limit of " + settings.maxBytes(),
            false);
      }
      return new Composed(messageId, out.toByteArray(), List.copyOf(all));
    } catch (MessagingException | IOException e) {
      throw new CapabilityException("cannot build the mail: " + e.getMessage(), false, e);
    }
  }

  private static MimeMultipart bodyOf(String text, String html) throws MessagingException {
    MimeMultipart alternative = new MimeMultipart("alternative");
    if (!text.isEmpty()) {
      MimeBodyPart plain = new MimeBodyPart();
      plain.setText(text, "UTF-8");
      alternative.addBodyPart(plain);
    }
    if (!html.isEmpty()) {
      MimeBodyPart part = new MimeBodyPart();
      part.setContent(html, "text/html; charset=UTF-8");
      alternative.addBodyPart(part);
    }
    return alternative;
  }

  /**
   * The attachments of a call. Each has a {@code name}, an optional {@code type} and its bytes
   * either as {@code content} (a rule that forwards what another capability returned) or as an
   * {@code upload}: the token of a file that a person chose. A token names a file in the uploads
   * directory and nothing else, so a call cannot read any other file.
   */
  private static List<MimeBodyPart> attachments(
      Map<String, Object> args, java.nio.file.Path uploads, long maxBytes)
      throws CapabilityException, MessagingException {
    List<MimeBodyPart> out = new ArrayList<>();
    if (args.get("attachments") == null) {
      return out;
    }
    if (!(args.get("attachments") instanceof List<?> list)) {
      throw new CapabilityException("attachments must be a list", false);
    }
    long total = 0;
    for (Object o : list) {
      if (!(o instanceof Map<?, ?> m) || !(m.get("name") instanceof String name)) {
        throw new CapabilityException(
            "every attachment needs a name and its bytes (content or upload)", false);
      }
      if (name.isBlank()
          || name.indexOf('\r') >= 0
          || name.indexOf('\n') >= 0
          || name.contains("/")) {
        throw new CapabilityException("the attachment name '" + name + "' is not allowed", false);
      }
      byte[] content;
      if (m.get("content") instanceof byte[] bytes) {
        content = bytes;
      } else if (m.get("upload") instanceof String token) {
        content = upload(uploads, token, name, maxBytes);
      } else {
        throw new CapabilityException(
            "the attachment '" + name + "' has neither content nor an upload", false);
      }
      total += content.length;
      if (total > maxBytes) {
        throw new CapabilityException(
            "the attachments are more than the limit of " + maxBytes + " bytes", false);
      }
      String type =
          m.get("type") instanceof String t && !t.isBlank() ? t : "application/octet-stream";
      if (type.indexOf('\r') >= 0 || type.indexOf('\n') >= 0) {
        throw new CapabilityException("the attachment type must not contain a line break", false);
      }
      MimeBodyPart part = new MimeBodyPart();
      part.setDataHandler(new DataHandler(new ByteArrayDataSource(content, type)));
      part.setFileName(name);
      part.setDisposition(MimeBodyPart.ATTACHMENT);
      out.add(part);
    }
    return out;
  }

  private static byte[] upload(java.nio.file.Path uploads, String token, String name, long max)
      throws CapabilityException {
    if (uploads == null) {
      throw new CapabilityException(
          "attachments by upload need the uploads directory, which the daemon did not give", false);
    }
    if (!TOKEN.matcher(token).matches()) {
      throw new CapabilityException("'" + token + "' is not the token of an uploaded file", false);
    }
    java.nio.file.Path file = uploads.resolve(token + ".part");
    try {
      if (!java.nio.file.Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        throw new CapabilityException(
            "the file for '" + name + "' is not there (uploaded files are removed after a day)",
            false);
      }
      if (java.nio.file.Files.size(file) > max) {
        throw new CapabilityException(
            "the file for '" + name + "' is more than the limit of " + max + " bytes", false);
      }
      return java.nio.file.Files.readAllBytes(file);
    } catch (java.io.IOException e) {
      throw new CapabilityException("cannot read the file for '" + name + "'", true, e);
    }
  }

  private static List<InternetAddress> addresses(Map<String, Object> args, String key)
      throws CapabilityException {
    String text = line(args, key);
    if (text.isEmpty()) {
      return List.of();
    }
    try {
      InternetAddress[] parsed = InternetAddress.parse(text, true);
      List<InternetAddress> out = new ArrayList<>();
      for (InternetAddress a : parsed) {
        if (a.isGroup()) {
          throw new CapabilityException(key + " must not contain a group: " + text, false);
        }
        a.validate();
        if (!a.getAddress().contains("@")) {
          throw new CapabilityException(key + " needs a domain: " + a.getAddress(), false);
        }
        out.add(a);
      }
      return out;
    } catch (AddressException e) {
      throw new CapabilityException(key + " is not a list of addresses: " + text, false);
    }
  }

  /** A header value: text with no line break in it. */
  private static String line(Map<String, Object> args, String key) throws CapabilityException {
    String v = string(args, key);
    if (v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\0') >= 0) {
      throw new CapabilityException(key + " must not contain a line break", false);
    }
    return v.strip();
  }

  private static String string(Map<String, Object> args, String key) throws CapabilityException {
    Object v = args.get(key);
    if (v == null) {
      return "";
    }
    if (!(v instanceof String s)) {
      throw new CapabilityException(key + " must be text", false);
    }
    return s;
  }

  private static String domain(InternetAddress a) {
    String address = a.getAddress();
    return address.substring(address.indexOf('@') + 1);
  }

  /** The mail as text, for messages and tests. */
  static String asText(byte[] raw) {
    return new String(raw, StandardCharsets.UTF_8);
  }
}
