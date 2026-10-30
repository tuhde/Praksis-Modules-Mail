// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.contacts;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityException;
import java.nio.file.Path;
import java.util.Map;

/** What the capabilities of the module share: no settings, and the book in the data directory. */
abstract class Base implements Capability {
  private Path directory;
  private Book book;

  @Override
  public void configure(Map<String, Object> settings) throws CapabilityException {
    if (!settings.isEmpty()) {
      throw new CapabilityException(
          "unknown contacts setting '" + settings.keySet().iterator().next() + "'", false);
    }
  }

  @Override
  public synchronized void useDataDirectory(Path directory) {
    this.directory = directory;
  }

  final synchronized Book book() throws CapabilityException {
    if (book == null) {
      book = Book.open(directory);
    }
    return book;
  }

  @Override
  public synchronized void close() {
    if (book != null) {
      book.release();
      book = null;
    }
  }
}
