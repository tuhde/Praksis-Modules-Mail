// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.smtp;

import it.uhde.praksis.capability.CapabilityException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a reply and a forward start from: the recipients, the subject, the threading headers and the
 * text with the original quoted. Nothing is sent or read here; it is the text that a person then
 * edits. The text starts with room for the answer, then the signature, then the quoted mail, which
 * is how most people write when the answer comes first.
 */
final class Replies {
  private static final Pattern REPLY_PREFIX =
      Pattern.compile(
          "^(\\s*(re|aw|sv|antw|vs)\\s*(\\[\\d+\\])?\\s*:\\s*)+", Pattern.CASE_INSENSITIVE);
  private static final Pattern FORWARD_PREFIX =
      Pattern.compile("^(\\s*(fwd?|wg|vs)\\s*:\\s*)+", Pattern.CASE_INSENSITIVE);

  /** Most ids kept in References, which grows with every reply. */
  private static final int MAX_REFERENCES = 20;

  /** The most characters of the original that are quoted. */
  private static final int MAX_QUOTED = 100_000;

  private final SmtpSettings settings;

  Replies(SmtpSettings settings) {
    this.settings = settings;
  }

  /** The fields of a reply, or a reply to all if {@code all}. */
  Map<String, Object> reply(Map<String, Object> mail, boolean all) throws CapabilityException {
    String replyTo = text(mail, "reply_to");
    String from = text(mail, "from");
    List<InternetAddress> to = new ArrayList<>(addresses(replyTo.isBlank() ? from : replyTo));
    List<InternetAddress> cc = new ArrayList<>();
    if (all) {
      to.addAll(addresses(text(mail, "to")));
      cc.addAll(addresses(text(mail, "cc")));
    }
    List<InternetAddress> seen = new ArrayList<>();
    List<InternetAddress> toOut = others(to, seen);
    List<InternetAddress> ccOut = others(cc, seen);
    if (toOut.isEmpty() && !all) {
      // a reply to yourself goes to the people you wrote to
      toOut = others(addresses(text(mail, "to")), seen);
    }

    String messageId = text(mail, "message_id").strip();
    String references = text(mail, "references").strip();
    String chain =
        references.isEmpty()
            ? messageId
            : (messageId.isEmpty() ? references : references + " " + messageId);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("to", join(toOut));
    out.put("cc", join(ccOut));
    out.put("subject", prefixed("Re:", REPLY_PREFIX, text(mail, "subject")));
    out.put("in_reply_to", messageId);
    out.put("references", cap(chain));
    String attribution =
        (text(mail, "date").isBlank() ? "" : "On " + text(mail, "date") + ", ")
            + (from.isBlank() ? "the sender" : from)
            + " wrote:";
    out.put(
        "text", "\n\n" + signature(mail) + attribution + "\n" + quote(text(mail, "text")) + "\n");
    return out;
  }

  /** The fields of a forward. */
  Map<String, Object> forward(Map<String, Object> mail) {
    StringBuilder body = new StringBuilder("\n\n").append(signature(mail));
    body.append("---------- Forwarded message ----------\n");
    for (String[] header :
        new String[][] {
          {"From", "from"}, {"Date", "date"}, {"Subject", "subject"}, {"To", "to"}, {"Cc", "cc"}
        }) {
      String value = text(mail, header[1]).replaceAll("[\\r\\n]+", " ").strip();
      if (!value.isEmpty()) {
        body.append(header[0]).append(": ").append(value).append('\n');
      }
    }
    body.append('\n').append(limited(text(mail, "text"))).append('\n');
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("to", "");
    out.put("cc", "");
    out.put("subject", prefixed("Fwd:", FORWARD_PREFIX, text(mail, "subject")));
    out.put("in_reply_to", "");
    out.put("references", "");
    out.put("text", body.toString());
    return out;
  }

  /**
   * The subject with its old prefixes replaced by one; no space after it if there is no subject.
   */
  private static String prefixed(String prefix, Pattern old, String subject) {
    String rest = old.matcher(subject).replaceFirst("").strip();
    return rest.isEmpty() ? prefix : prefix + " " + rest;
  }

  private static String signature(Map<String, Object> mail) {
    String sig = text(mail, "signature").strip();
    return sig.isEmpty() ? "" : "-- \n" + sig + "\n\n";
  }

  /**
   * The original with every line behind a "&gt; "; a line that is quoted already gets one more ">".
   */
  static String quote(String original) {
    String text = limited(original).replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
    if (text.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    for (String line : text.split("\n", -1)) {
      out.append(line.startsWith(">") ? ">" : "> ").append(line).append('\n');
    }
    return out.substring(0, out.length() - 1);
  }

  private static String limited(String s) {
    return s.length() > MAX_QUOTED ? s.substring(0, MAX_QUOTED) + "\n[cut]" : s;
  }

  /** Keeps the first id and the last ones: the chain of a long thread. */
  private static String cap(String chain) {
    String[] ids = chain.isEmpty() ? new String[0] : chain.split("\\s+");
    if (ids.length <= MAX_REFERENCES) {
      return String.join(" ", ids);
    }
    List<String> kept = new ArrayList<>();
    kept.add(ids[0]);
    for (int i = ids.length - (MAX_REFERENCES - 1); i < ids.length; i++) {
      kept.add(ids[i]);
    }
    return String.join(" ", kept);
  }

  /**
   * The addresses that are not mine and were not named before; the ones kept are noted in {@code
   * seen}.
   */
  private List<InternetAddress> others(List<InternetAddress> in, List<InternetAddress> seen) {
    List<InternetAddress> out = new ArrayList<>();
    for (InternetAddress a : in) {
      String address = a.getAddress().toLowerCase(Locale.ROOT);
      boolean mine = settings.mayUse(a);
      boolean again = seen.stream().anyMatch(s -> s.getAddress().equalsIgnoreCase(address));
      if (!mine && !again) {
        out.add(a);
        seen.add(a);
      }
    }
    return out;
  }

  private static List<InternetAddress> addresses(String header) throws CapabilityException {
    if (header.isBlank()) {
      return List.of();
    }
    try {
      List<InternetAddress> out = new ArrayList<>();
      for (InternetAddress a : InternetAddress.parseHeader(header, false)) {
        if (!a.isGroup() && a.getAddress() != null && a.getAddress().contains("@")) {
          out.add(a);
        }
      }
      return out;
    } catch (AddressException e) {
      return List.of(); // an address header that is garbage gives no recipients, not an error
    }
  }

  private static String join(List<InternetAddress> addresses) {
    List<String> out = new ArrayList<>();
    for (InternetAddress a : addresses) {
      out.add(a.toUnicodeString());
    }
    return String.join(", ", out);
  }

  private static String text(Map<String, Object> mail, String key) {
    return mail.get(key) instanceof String s ? s : "";
  }
}
