// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Event;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotifyShowTest {
  private static CapabilityContext context(boolean dry) {
    return new CapabilityContext() {
      @Override
      public boolean dryRun() {
        return dry;
      }

      @Override
      public String idempotencyKey() {
        return "k";
      }

      @Override
      public Optional<Event> event() {
        return Optional.empty();
      }
    };
  }

  @TempDir Path dir;

  /** A program that writes the arguments it got, one per line, to a file. */
  private Path recorder() throws Exception {
    Path script = dir.resolve("fake-notify");
    Files.writeString(
        script,
        "#!/bin/sh\nfor a in \"$@\"; do printf '%s\\n' \"$a\" >> "
            + dir.resolve("calls")
            + "; done\nprintf -- '--end--\\n' >> "
            + dir.resolve("calls")
            + "\n");
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
    return script;
  }

  private NotifyShow shower(Object... kv) throws Exception {
    NotifyShow show = new NotifyShow();
    java.util.Map<String, Object> settings = new java.util.HashMap<>();
    settings.put("command", List.of(recorder().toString()));
    for (int i = 0; i < kv.length; i += 2) {
      settings.put((String) kv[i], kv[i + 1]);
    }
    show.configure(settings);
    return show;
  }

  private List<String> calls() throws Exception {
    Path f = dir.resolve("calls");
    return Files.exists(f) ? Files.readAllLines(f) : List.of();
  }

  @Test
  void aNotificationIsShownWithTheTitleAndTheBodyAsSeparateArgumentsAfterTheSeparator()
      throws Exception {
    Map<String, Object> answer =
        shower().invoke(context(false), Map.of("title", "Anna", "body", "Lunch tomorrow?"));

    assertThat(answer).containsEntry("shown", true).containsEntry("suppressed", 0L);
    assertThat(calls()).containsExactly("--", "Anna", "Lunch tomorrow?", "--end--");
  }

  @Test
  void textFromMailCannotBecomeAnOptionAMarkupOrASecondLine() throws Exception {
    NotifyShow show = shower();

    show.invoke(
        context(false),
        Map.of(
            "title", "--icon=/etc/passwd\nline two\u0000",
            "body",
                "<b>bold</b> & <img src=file:///etc/passwd> $(rm -rf ~) `x` ; touch /tmp/pwned"));

    List<String> calls = calls();
    assertThat(calls.get(0)).isEqualTo("--"); // everything after is data
    assertThat(calls.get(1)).isEqualTo("--icon=/etc/passwd line two");
    assertThat(calls.get(2))
        .isEqualTo(
            "&lt;b&gt;bold&lt;/b&gt; &amp; &lt;img src=file:///etc/passwd&gt; $(rm -rf ~) `x` ; touch /tmp/pwned");
    assertThat(calls).hasSize(4); // two arguments and the marker: nothing was run by a shell
    assertThat(Path.of("/tmp/pwned")).doesNotExist();
  }

  @Test
  void textIsCutWithoutBreakingAnEntity() {
    assertThat(NotifyShow.clean("x".repeat(500), 80)).hasSize(83).endsWith("...");
    String ampersands = "&".repeat(100);
    String cut = NotifyShow.clean(ampersands, 80);
    assertThat(cut).endsWith("...");
    assertThat(cut.replace("&amp;", "").replace("...", "")).isEmpty(); // no half entity
    assertThat(NotifyShow.clean(null, 80)).isEmpty();
    assertThat(NotifyShow.clean(7, 80)).isEmpty();
  }

  @Test
  void aBurstIsLimitedAndTheNextOneSaysHowManyWereLeftOut() throws Exception {
    NotifyShow show = shower("max_per_minute", 2L);

    assertThat(show.invoke(context(false), Map.of("title", "1"))).containsEntry("shown", true);
    assertThat(show.invoke(context(false), Map.of("title", "2"))).containsEntry("shown", true);
    Map<String, Object> third = show.invoke(context(false), Map.of("title", "3"));
    Map<String, Object> fourth = show.invoke(context(false), Map.of("title", "4"));

    assertThat(third).containsEntry("shown", false).containsEntry("suppressed", 1L);
    assertThat(fourth).containsEntry("suppressed", 2L);
    assertThat(calls().stream().filter(l -> l.equals("--end--"))).hasSize(2);
  }

  @Test
  void aDryRunShowsNothingAndCountsNothing() throws Exception {
    NotifyShow show = shower("max_per_minute", 1L);
    for (int i = 0; i < 3; i++) {
      assertThat(show.invoke(context(true), Map.of("title", "x"))).containsEntry("shown", false);
    }
    assertThat(calls()).isEmpty();
    assertThat(show.invoke(context(false), Map.of("title", "x"))).containsEntry("shown", true);
  }

  @Test
  void whatCannotBeDoneIsSaid() throws Exception {
    assertThatThrownBy(() -> shower().invoke(context(false), Map.of("title", "  ")))
        .hasMessageContaining("needs a title");
    NotifyShow missing = new NotifyShow();
    missing.configure(Map.of("command", List.of("/no/such/program")));
    assertThatThrownBy(() -> missing.invoke(context(false), Map.of("title", "x")))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining("cannot run '/no/such/program'");
    NotifyShow failing = new NotifyShow();
    failing.configure(Map.of("command", List.of("false")));
    assertThatThrownBy(() -> failing.invoke(context(false), Map.of("title", "x")))
        .hasMessageContaining("failed with exit code");
    assertThatThrownBy(() -> new NotifyShow().configure(Map.of("nope", 1L)))
        .hasMessageContaining("unknown notify setting 'nope'");
    assertThatThrownBy(() -> new NotifyShow().configure(Map.of("command", "notify-send")))
        .hasMessageContaining("must be a list");
    assertThatThrownBy(() -> new NotifyShow().configure(Map.of("max_per_minute", 0L)))
        .hasMessageContaining("max_per_minute");
  }
}
