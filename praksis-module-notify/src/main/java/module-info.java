// SPDX-License-Identifier: Apache-2.0
/** Shows a desktop notification: {@code notify.show}. */
module it.uhde.praksis.module.notify {
  requires it.uhde.praksis.capability;
  requires java.logging;

  provides it.uhde.praksis.capability.Capability with
      it.uhde.praksis.module.notify.NotifyShow;
}
