// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.notify;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Shows a notification with {@code notify-send} (or the command of the setting {@code command}).
 *
 * <p>The title and the body come from mail, which is untrusted: they go to the program as separate
 * arguments after {@code --}, never through a shell, with control characters removed, {@code &},
 * {@code <} and {@code >} escaped (a notification server may read markup) and the length limited.
 * At most {@code max_per_minute} notifications are shown; the others are counted, and the next one
 * that is shown says how many were left out, so a burst of mail does not cover the screen.
 *
 * <p>Settings: {@code command} (a list; default {@code ["notify-send", "--app-name=Praksis"]}) and
 * {@code max_per_minute} (default 5).
 */
public final class NotifyShow implements Capability {
  private static final int MAX_TITLE = 80;
  private static final int MAX_BODY = 240;
  private static final long WINDOW_MILLIS = 60_000;

  private List<String> command = List.of("notify-send", "--app-name=Praksis");
  private int maxPerMinute = 5;
  private final Deque<Long> shownAt = new ArrayDeque<>();
  private int suppressed;

  @Override
  public String name() {
    return "notify.show";
  }

  @Override
  public void configure(Map<String, Object> settings) throws CapabilityException {
    for (String key : settings.keySet()) {
      if (!Set.of("command", "max_per_minute").contains(key)) {
        throw new CapabilityException("unknown notify setting '" + key + "'", false);
      }
    }
    if (settings.containsKey("command")) {
      if (!(settings.get("command") instanceof List<?> list)
          || list.isEmpty()
          || !list.stream().allMatch(e -> e instanceof String s && !s.isBlank())) {
        throw new CapabilityException(
            "notify setting 'command' must be a list with the program and its arguments", false);
      }
      command = list.stream().map(String.class::cast).toList();
    }
    if (settings.containsKey("max_per_minute")) {
      if (!(settings.get("max_per_minute") instanceof Long n) || n < 1 || n > 600) {
        throw new CapabilityException(
            "notify setting 'max_per_minute' must be a whole number from 1 to 600", false);
      }
      maxPerMinute = n.intValue();
    }
  }

  @Override
  public synchronized Map<String, Object> invoke(
      CapabilityContext context, Map<String, Object> args) throws CapabilityException {
    String title = clean(args.get("title"), MAX_TITLE);
    String body = clean(args.get("body"), MAX_BODY);
    if (title.isEmpty()) {
      throw new CapabilityException("a notification needs a title", false);
    }
    long now = System.currentTimeMillis();
    while (!shownAt.isEmpty() && now - shownAt.peekFirst() > WINDOW_MILLIS) {
      shownAt.removeFirst();
    }
    if (shownAt.size() >= maxPerMinute) {
      if (!context.dryRun()) {
        suppressed++;
      }
      return Map.of("shown", false, "suppressed", (long) suppressed);
    }
    if (context.dryRun()) {
      return Map.of("shown", false, "suppressed", (long) suppressed);
    }
    if (suppressed > 0) {
      body = (body.isEmpty() ? "" : body + " ") + "(and " + suppressed + " more)";
    }
    run(title, body);
    shownAt.addLast(now);
    suppressed = 0;
    return Map.of("shown", true, "suppressed", 0L);
  }

  private void run(String title, String body) throws CapabilityException {
    List<String> line = new ArrayList<>(command);
    line.add("--");
    line.add(title);
    if (!body.isEmpty()) {
      line.add(body);
    }
    try {
      Process p =
          new ProcessBuilder(line)
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      p.getOutputStream().close();
      if (!p.waitFor(10, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new CapabilityException("'" + command.get(0) + "' did not finish", true);
      }
      if (p.exitValue() != 0) {
        throw new CapabilityException(
            "'" + command.get(0) + "' failed with exit code " + p.exitValue(), true);
      }
    } catch (IOException e) {
      throw new CapabilityException(
          "cannot run '" + command.get(0) + "': " + e.getMessage(), false, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CapabilityException("interrupted", true, e);
    }
  }

  /** Text for a notification: one line, no control characters, no markup, not too long. */
  static String clean(Object value, int max) {
    if (!(value instanceof String s)) {
      return "";
    }
    String text =
        s.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]+", " ")
            .replaceAll("\\s+", " ")
            .strip()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    if (text.length() > max) {
      int cut = max;
      // not in the middle of an entity that was just made
      int amp = text.lastIndexOf('&', cut);
      if (amp >= 0 && text.indexOf(';', amp) >= cut) {
        cut = amp;
      }
      text = text.substring(0, Math.max(0, cut)).stripTrailing() + "...";
    }
    return text;
  }
}
