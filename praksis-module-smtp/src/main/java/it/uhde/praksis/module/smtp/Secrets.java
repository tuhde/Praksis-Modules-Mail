// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.smtp;

import it.uhde.praksis.capability.CapabilityException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Gets a password by running a command, so that it is never stored in a file of Praksis. */
final class Secrets {
  private static final long TIMEOUT_SECONDS = 30;

  private Secrets() {}

  /**
   * Runs the command and returns what it printed, without the final line break. The output is never
   * put into a message or a log.
   */
  static String read(List<String> command) throws CapabilityException {
    Process p;
    try {
      p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    } catch (IOException e) {
      throw new CapabilityException(
          "cannot run password_command '" + command.get(0) + "': " + e.getMessage(), false, e);
    }
    try {
      p.getOutputStream().close();
      byte[] out = p.getInputStream().readNBytes(64 * 1024);
      if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new CapabilityException(
            "password_command '"
                + command.get(0)
                + "' did not finish within "
                + TIMEOUT_SECONDS
                + " seconds",
            true);
      }
      if (p.exitValue() != 0) {
        throw new CapabilityException(
            "password_command '" + command.get(0) + "' failed with exit code " + p.exitValue(),
            true);
      }
      String password = new String(out, StandardCharsets.UTF_8);
      if (password.endsWith("\n")) {
        password = password.substring(0, password.length() - (password.endsWith("\r\n") ? 2 : 1));
      }
      if (password.isEmpty()) {
        throw new CapabilityException(
            "password_command '" + command.get(0) + "' printed nothing", false);
      }
      return password;
    } catch (IOException e) {
      throw new CapabilityException(
          "cannot read the output of password_command '" + command.get(0) + "'", true, e);
    } catch (InterruptedException e) {
      p.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new CapabilityException("interrupted while running password_command", true, e);
    }
  }
}
