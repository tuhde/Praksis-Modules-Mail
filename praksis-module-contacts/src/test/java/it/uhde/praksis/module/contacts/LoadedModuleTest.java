// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.contacts;

import static org.assertj.core.api.Assertions.assertThat;

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
  void theModuleWorksInItsOwnLayerAndRemembersWhatItWasToldInTheDataDirectory() throws Exception {
    Path modules = Files.createDirectories(dir.resolve("modules"));
    Path moduleDir = Files.createDirectories(modules.resolve("contacts"));
    Files.copy(Path.of("module.toml"), moduleDir.resolve("module.toml"));
    pack(Path.of("target/classes"), moduleDir.resolve("praksis-module-contacts.jar"));
    for (Path lib : libraries()) {
      Files.copy(lib, moduleDir.resolve(lib.getFileName()));
    }
    Path lock = dir.resolve("modules.lock");
    ModuleLoader.lock(modules, lock);

    try (LoadedModules loaded = ModuleLoader.load(modules, lock, Map.of())) {
      assertThat(loaded.capabilities())
          .containsOnlyKeys("contacts.add", "contacts.search", "contacts.remove");
      assertThat(loaded.catalog().moduleNames()).containsExactly("contacts");
      assertThat(loaded.catalog().capability("contacts.remove").orElseThrow().effect().id())
          .isEqualTo("destructive");
      loaded.useDataDirectory(dir.resolve("data"));
      assertThat(dir.resolve("data/contacts")).isDirectory();
    }
  }
}
