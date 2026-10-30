// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.smtp;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.engine.LoadedModules;
import it.uhde.praksis.engine.ModuleLoader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loads the module in a layer of its own, as the daemon does. */
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
                  || n.startsWith("angus-activation-");
            })
        .toList();
  }

  @Test
  void theModuleWorksInItsOwnLayerAndSendingIsExternal() throws Exception {
    Path modules = Files.createDirectories(dir.resolve("modules"));
    Path moduleDir = Files.createDirectories(modules.resolve("smtp"));
    Files.copy(Path.of("module.toml"), moduleDir.resolve("module.toml"));
    pack(Path.of("target/classes"), moduleDir.resolve("praksis-module-smtp.jar"));
    for (Path lib : libraries()) {
      Files.copy(lib, moduleDir.resolve(lib.getFileName()));
    }
    Path lock = dir.resolve("modules.lock");
    ModuleLoader.lock(modules, lock);

    try (Server server = new Server();
        LoadedModules loaded =
            ModuleLoader.load(modules, lock, Map.of("smtp", server.settings()))) {
      assertThat(loaded.capabilities())
          .containsOnlyKeys(
              "mail.send", "mail.compose", "mail.reply", "mail.forward", "mail.senders");
      assertThat(loaded.catalog().capability("mail.send").orElseThrow().effect())
          .isEqualTo(EffectClass.EXTERNAL);
      assertThat(loaded.capabilities().get("mail.send").getClass().getModule().getLayer())
          .isNotSameAs(ModuleLayer.boot());

      // sending through the layer is tried with the real image (SmtpIT in the integration tests):
      // this test JVM has Angus Mail on its own module path, which the daemon's image has not
      loaded.useDataDirectory(dir.resolve("data"));
      assertThat(dir.resolve("data/smtp")).isDirectory();
    }
  }
}
