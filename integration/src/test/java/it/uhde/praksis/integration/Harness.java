// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.protocol.Client;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** A Praksis home directory, and real daemon and CLI processes run from the runtime image. */
final class Harness implements AutoCloseable {
  static final String RULE =
      """
      id = "mail.archive"
      effect = "local-write"
      needs = ["mail.move"]

      [trigger]
      kind = "event"
      type = "mail.received"

      [[action]]
      id = "move"
      capability = "mail.move"
      args = { message = { cel = "event.data.message" }, folder = "Archive" }
      """;

  /** The jlink image from praksis-dist: the daemon and the CLI run exactly as shipped. */
  private final Path image =
      Path.of(System.getProperty("praksis.image")).toAbsolutePath().normalize();

  private final List<Process> processes = new ArrayList<>();

  /** Short, because Unix socket paths are. */
  final Path home;

  final Path mail;

  Harness() throws Exception {
    home = Files.createTempDirectory("pk");
    mail = home.resolve("mail");
    for (String d : new String[] {"new", "cur", "tmp"}) {
      Files.createDirectories(mail.resolve(d));
    }
    // modules and the expression layer come from the image; the lockfile is made by the CLI
    assertThat(cli("modules", "lock").exit()).isZero();
    // the mail modules are not in the image: they come from their archives, as for a person
    for (String module : List.of("imap", "smtp", "contacts", "notify")) {
      CliResult installed = cli("modules", "install", archive(module).toString());
      assertThat(installed.exit()).as(installed.output()).isZero();
    }
    Files.writeString(
        home.resolve("praksis.toml"),
        "format_version = 1\n[module.maildir]\nroot = '" + mail + "'\npoll_interval_ms = 20\n");
    Files.createDirectories(home.resolve("ruleset/rules"));
    Files.writeString(
        home.resolve("ruleset/ruleset.toml"), "format_version = 1\nname = \"mail\"\n");
    Files.writeString(home.resolve("ruleset/rules/mail.archive.toml"), RULE);
    grantMoves(java.util.Map.of("ruleset", List.of("Archive")));
  }

  /** Replaces {@code policy.toml}: each layer may move mail to the folders listed for it. */
  void grantMoves(java.util.Map<String, List<String>> foldersByLayer) throws Exception {
    StringBuilder policy = new StringBuilder("format_version = 1\n");
    for (var layer : new java.util.TreeMap<>(foldersByLayer).entrySet()) {
      policy
          .append("\n[[grant]]\nid = \"")
          .append(layer.getKey())
          .append("-moves\"\nlayer = \"")
          .append(layer.getKey())
          .append("\"\ncapability = \"mail.move\"\n[grant.args]\nfolder = { one_of = [")
          .append(String.join(", ", layer.getValue().stream().map(f -> "\"" + f + "\"").toList()))
          .append("] }\n");
    }
    Files.writeString(home.resolve("policy.toml"), policy.toString());
  }

  /** The git settings of the processes: a file of the test instead of the user's own. */
  Path gitConfig() {
    return home.resolve("gitconfig");
  }

  /** The archive that the build of a module made. */
  static Path archive(String module) {
    return Path.of(System.getProperty("praksis.modules"))
        .resolve("praksis-module-" + module + "/target/" + module + ".praksis-module")
        .toAbsolutePath()
        .normalize();
  }

  Path socket() {
    return home.resolve("praksis.sock");
  }

  private List<String> java(String module, String... args) {
    List<String> command =
        new ArrayList<>(List.of(image.resolve("bin/java").toString(), "--module", module));
    command.addAll(List.of(args));
    return command;
  }

  /** Starts the daemon itself (not through the CLI) and waits until it answers. */
  Process startDaemon() throws Exception {
    List<String> command =
        java("it.uhde.praksis.daemon/it.uhde.praksis.daemon.Main", "--home", home.toString());
    command.add(1, "--enable-native-access=org.xerial.sqlitejdbc");
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(home.resolve("daemon.log").toFile()));
    builder.environment().put("GIT_CONFIG_GLOBAL", gitConfig().toString());
    Process p = builder.start();
    processes.add(p);
    await(
        "the daemon to answer",
        () -> {
          if (!p.isAlive()) {
            throw new AssertionError(
                "the daemon exited with " + p.exitValue() + ":\n" + daemonLog());
          }
          return status() != null;
        });
    return p;
  }

  /** Kills with SIGKILL, so nothing gets a chance to clean up, and waits for the exit. */
  void kill(Process p) throws InterruptedException {
    p.destroyForcibly();
    p.waitFor();
  }

  /** Runs the CLI and returns its exit code and everything it printed. */
  record CliResult(int exit, String output) {}

  CliResult cli(String... args) throws Exception {
    List<String> command =
        java("it.uhde.praksis.cli/it.uhde.praksis.cli.Praksis", args[0], "--home", home.toString());
    command.addAll(List.of(args).subList(1, args.length));
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().put("GIT_CONFIG_GLOBAL", gitConfig().toString());
    Process p = builder.start();
    p.getOutputStream().close();
    String output = new String(p.getInputStream().readAllBytes());
    return new CliResult(p.waitFor(), output);
  }

  /** The daemon's status, or null if it does not answer. */
  Response.StatusReport status() {
    try (Client client = Client.connect(socket())) {
      return client.send(new Request.Status()) instanceof Response.StatusReport s ? s : null;
    } catch (IOException e) {
      return null;
    }
  }

  Response ask(Request request) throws IOException {
    try (Client client = Client.connect(socket())) {
      return client.send(request);
    }
  }

  List<Response.LogLines.Line> log() throws IOException {
    return ((Response.LogLines) ask(new Request.Log(1000))).lines();
  }

  /** Delivers a mail the way a mail delivery agent does: write in tmp, then rename into new. */
  void deliver(String uniqueName, String subject) throws IOException {
    Path tmp = mail.resolve("tmp").resolve(uniqueName);
    Files.writeString(tmp, "From: a@example.org\nSubject: " + subject + "\n\nbody\n");
    Files.move(tmp, mail.resolve("new").resolve(uniqueName));
  }

  /** Delivers a mail with exactly these header lines. */
  void deliverWithHeaders(String uniqueName, String... headers) throws IOException {
    Path tmp = mail.resolve("tmp").resolve(uniqueName);
    Files.writeString(tmp, String.join("\n", headers) + "\n\nbody\n");
    Files.move(tmp, mail.resolve("new").resolve(uniqueName));
  }

  /** Asks the daemon to stop and waits until the process is gone. */
  void stop(Process p) throws Exception {
    assertThat(ask(new Request.Stop())).isEqualTo(new Response.Stopped());
    if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
      // SIGQUIT makes the JVM print all its threads to its output, which is daemon.log
      new ProcessBuilder("kill", "-3", String.valueOf(p.pid())).start().waitFor();
      Thread.sleep(1000);
      throw new AssertionError(
          "the daemon did not exit within 30 seconds; its threads:\n" + daemonLog());
    }
  }

  List<String> names(String dir) throws IOException {
    Path d = mail.resolve(dir);
    if (!Files.isDirectory(d)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(d)) {
      return files.map(f -> f.getFileName().toString()).sorted(Comparator.naturalOrder()).toList();
    }
  }

  String daemonLog() {
    try {
      return Files.readString(home.resolve("daemon.log"));
    } catch (IOException e) {
      return "(no daemon.log)";
    }
  }

  /** A condition that may fail with an exception; the exception fails the test. */
  @FunctionalInterface
  interface Condition {
    boolean holds() throws Exception;
  }

  static void await(String what, Condition condition) throws Exception {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!condition.holds()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("timed out waiting for " + what);
      }
      Thread.sleep(10);
    }
  }

  @Override
  public void close() throws IOException {
    Response.StatusReport s = status();
    if (s != null) {
      ProcessHandle.of(s.pid()).ifPresent(ProcessHandle::destroyForcibly);
    }
    processes.forEach(Process::destroyForcibly);
    try (Stream<Path> files = Files.walk(home)) {
      for (Path f : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(f);
      }
    }
  }
}
