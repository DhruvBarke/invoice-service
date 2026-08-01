package com.example.invoice.service.alerting.publish;

import com.example.invoice.service.alerting.AlertingSwitches;
import com.example.invoice.service.domain.port.out.AlertNotifier;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Applies {@link AlertingSwitches} in front of the email notifier.
 *
 * <p>The only place the switches are consulted. Detection, recording and blocking never read them, so
 * no combination of settings here can cause unusable data to reach invoice registration.
 */
public final class SwitchGatedNotifier implements AlertNotifier {

    private final AlertingSwitches switches;
    private final AlertNotifier delegate;
    private final LongAdder passed = new LongAdder();
    private final LongAdder suppressed = new LongAdder();

    public SwitchGatedNotifier(AlertingSwitches switches, AlertNotifier delegate) {
        this.switches = Objects.requireNonNull(switches, "switches");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void notify(Notification notification) {
        if (notification == null) {
            return;
        }
        if (!switches.shouldEmail(notification.type(), notification.servability(),
                notification.flow())) {
            suppressed.increment();
            return;
        }
        delegate.notify(notification);
        passed.increment();
    }

    public long passedCount() {
        return passed.sum();
    }

    public long suppressedCount() {
        return suppressed.sum();
    }
}
