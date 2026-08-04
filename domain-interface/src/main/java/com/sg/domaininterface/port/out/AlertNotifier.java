package com.sg.domaininterface.port.out;

import com.sg.domaininterface.model.party.Flow;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.rule.party.AnomalyType;
import com.sg.domaininterface.rule.party.Servability;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Tells a human about a defect.
 *
 * <p>Named for the role, not the medium: today the only implementation sends email, but that is a
 * deployment choice rather than something the domain asserts.
 *
 * <p><b>Notification is not the record.</b> {@link QuarantineStore} is. An implementation may drop,
 * throttle or aggregate freely, and disabling notification entirely loses nothing permanent — every
 * defect remains in the store, correctable. That asymmetry is what makes a single fragile channel an
 * acceptable design.
 *
 * <p>Implementations must return promptly and must not throw; callers wrap invocations defensively,
 * because a failing notifier must never turn a working party lookup into an error.
 */
@FunctionalInterface
public interface AlertNotifier {

    void notify(Notification notification);

    /** Notification does nothing. Detection, recording and blocking are unaffected. */
    static AlertNotifier none() {
        return notification -> { };
    }

    /**
     * @param fingerprint the quarantine row's fingerprint, so any aggregation an implementation
     *                    performs agrees with the store's notify-once gate on what is "the same
     *                    problem"
     * @param samples     capped by the implementation; a notification must never carry an unbounded
     *                    payload
     */
    record Notification(
            AnomalyType type,
            Servability servability,
            Flow flow,
            String fingerprint,
            String message,
            Instant occurredAt,
            Map<String, String> context,
            List<PartyRegistrationDetails> samples
    ) {
        public Notification {
            context = context == null ? Map.of() : Map.copyOf(context);
            samples = samples == null ? List.of() : List.copyOf(samples);
        }
    }
}
