// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.engine.LoadedModules;
import it.uhde.praksis.engine.ModuleLoader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loads the module from a directory in a layer of its own, as the daemon does, with the jars of
 * Angus Mail next to it, and uses it against an IMAP server.
 */
class LoadedModuleTest {
  @TempDir Path dir;

  private static void pack(Path classes, Path jar) throws IOException {
    try (OutputStream out = Files.newOutputStream(jar);
        JarOutputStream jos = new JarOutputStream(out);
        Stream<Path> files = Files.walk(classes)) {
      for (Path f : files.filter(Files::isRegularFile).toList()) {
        jos.putNextEntry(new JarEntry(classes.relativize(f).toString().replace('\\', '/')));
        jos.write(Files.readAllBytes(f));
        jos.closeEntry();
      }
    }
  }

  /** The jars the module needs, found on the module path or class path of the test. */
  private static List<Path> libraries() {
    String paths =
        System.getProperty("jdk.module.path", "")
            + java.io.File.pathSeparator
            + System.getProperty("java.class.path", "");
    return Stream.of(paths.split(java.io.File.pathSeparator))
        .filter(p -> !p.isEmpty())
        .map(Path::of)
        .filter(
            p -> {
              String n = p.getFileName().toString();
              return n.startsWith("angus-mail-")
                  || n.startsWith("jakarta.mail-api-")
                  || n.startsWith("jakarta.activation-api-")
                  || n.startsWith("angus-activation-")
                  || n.startsWith("jsoup-")
                  || p.toString().contains("praksis-mail-html");
            })
        .toList();
  }

  @Test
  void theModuleWorksInItsOwnLayerWithItsLibrariesNextToIt() throws Exception {
    Path modules = Files.createDirectories(dir.resolve("modules"));
    Path moduleDir = Files.createDirectories(modules.resolve("imap"));
    Files.copy(Path.of("module.toml"), moduleDir.resolve("module.toml"));
    pack(Path.of("target/classes"), moduleDir.resolve("praksis-module-imap.jar"));
    List<Path> libs = libraries();
    for (Path lib : libs) {
      if (Files.isDirectory(lib)) {
        pack(lib, moduleDir.resolve("praksis-mail-html.jar")); // the reactor build: classes
      } else {
        Files.copy(lib, moduleDir.resolve(lib.getFileName()));
      }
    }
    Path lock = dir.resolve("modules.lock");
    ModuleLoader.lock(modules, lock);

    try (Server server = new Server();
        LoadedModules loaded =
            ModuleLoader.load(modules, lock, Map.of("imap", server.settings()))) {
      assertThat(loaded.capabilities())
          .containsOnlyKeys(
              "mail.move",
              "mail.folders",
              "mail.list",
              "mail.get",
              "mail.search",
              "mail.mark",
              "mail.delete",
              "mail.attachment",
              "mail.append",
              "mail.accounts");
      assertThat(loaded.sources()).extracting(s -> s.name()).containsExactly("imap.inbox");
      assertThat(loaded.catalog().eventType("mail.received")).isPresent();
      assertThat(loaded.capabilities().get("mail.move").getClass().getModule().getLayer())
          .isNotSameAs(ModuleLayer.boot());

      server.deliver(false, "Subject: through the layer", "Message-ID: <l@example.org>");
      List<Event> events = new CopyOnWriteArrayList<>();
      loaded.startSources(events::add);
      long end = System.nanoTime() + 10_000_000_000L;
      while (events.isEmpty() && System.nanoTime() < end) {
        Thread.sleep(10);
      }
      loaded.stopSources();
      assertThat(events).hasSize(1);
      assertThat(events.get(0).data()).containsEntry("subject", "through the layer");

      Capability move = loaded.capabilities().get("mail.move");
      Map<String, Object> result =
          move.invoke(
              new CapabilityContext() {
                public boolean dryRun() {
                  return false;
                }

                public String idempotencyKey() {
                  return "k";
                }

                public Optional<Event> event() {
                  return Optional.empty();
                }
              },
              Map.of("message", events.get(0).data().get("message"), "folder", "Archive"));
      assertThat((String) result.get("message")).startsWith("imap:");
    }
  }
}
