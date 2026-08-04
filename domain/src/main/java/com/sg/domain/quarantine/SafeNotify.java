package com.sg.domain.quarantine;

import com.sg.domaininterface.port.out.AlertNotifier;

/** Publishes defensively: a notifier failure is logged and swallowed, never propagated. */
final class SafeNotify {

    private static final System.Logger LOG = System.getLogger(SafeNotify.class.getName());

    private SafeNotify() { }

    static void publish(AlertNotifier notifier, AlertNotifier.Notification notification) {
        if (notifier == null || notification == null) {
            return;
        }
        try {
            notifier.notify(notification);
        } catch (RuntimeException | Error e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Notification failed; dropping " + notification.type(), e);
        }
    }
}
