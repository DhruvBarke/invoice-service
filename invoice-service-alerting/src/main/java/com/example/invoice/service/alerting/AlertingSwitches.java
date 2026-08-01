package com.example.invoice.service.alerting;

import com.example.invoice.service.domain.model.Flow;
import com.example.invoice.service.domain.rule.AnomalyType;
import com.example.invoice.service.domain.rule.Servability;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime control over email.
 *
 * <p><b>Gates notification only.</b> Never detection, never recording, never blocking. Turning
 * everything off here silences mail; it cannot cause a record with no SIREN to reach invoice
 * registration. The separation is structural rather than documentary: this class is read exclusively
 * by {@code SwitchGatedNotifier}, and nothing in the blocking path can see it.
 *
 * <p><b>Read per notification, not at construction.</b> That is what makes these usable during an
 * incident: when a referential migration starts generating thousands of legitimate defects, mute the
 * mail from an operations endpoint immediately, without a restart and without losing the quarantine
 * rows needed for reconciliation afterwards.
 */
public final class AlertingSwitches {

    /** Master switch for email. False silences everything, whatever the finer settings say. */
    private final AtomicBoolean emailEnabled = new AtomicBoolean(true);
    private final AtomicReference<Set<AnomalyType>> anomalyTypes =
            new AtomicReference<>(EnumSet.allOf(AnomalyType.class));
    private final AtomicReference<Set<Flow>> flows =
            new AtomicReference<>(EnumSet.allOf(Flow.class));
    /** When BLOCKING, only defects that stop processing are emailed. */
    private final AtomicReference<Servability> minimumServability =
            new AtomicReference<>(Servability.SERVABLE);

    public static AlertingSwitches allEnabled() {
        return new AlertingSwitches();
    }

    /** Email silenced. Detection, recording and blocking continue unaffected. */
    public static AlertingSwitches emailDisabled() {
        AlertingSwitches s = new AlertingSwitches();
        s.setEmailEnabled(false);
        return s;
    }

    /** Evaluated most-general-first, so the master switch short-circuits everything. */
    public boolean shouldEmail(AnomalyType type, Servability servability, Flow flow) {
        if (!emailEnabled.get()) {
            return false;
        }
        if (minimumServability.get() == Servability.BLOCKING && servability != Servability.BLOCKING) {
            return false;
        }
        if (!anomalyTypes.get().contains(type)) {
            return false;
        }
        return flows.get().contains(flow);
    }

    public void setEmailEnabled(boolean value) {
        emailEnabled.set(value);
    }

    public boolean isEmailEnabled() {
        return emailEnabled.get();
    }

    public void setAnomalyTypes(Collection<AnomalyType> value) {
        anomalyTypes.set(copyOf(value, AnomalyType.class));
    }

    /** Mute one defect type — e.g. a missing SIRET that is expected during a migration. */
    public void muteAnomaly(AnomalyType type) {
        anomalyTypes.updateAndGet(current -> {
            EnumSet<AnomalyType> next = EnumSet.noneOf(AnomalyType.class);
            next.addAll(current);
            next.remove(type);
            return next;
        });
    }

    public void unmuteAnomaly(AnomalyType type) {
        anomalyTypes.updateAndGet(current -> {
            EnumSet<AnomalyType> next = EnumSet.copyOf(current);
            next.add(type);
            return next;
        });
    }

    public void setFlows(Collection<Flow> value) {
        flows.set(copyOf(value, Flow.class));
    }

    public void setMinimumServability(Servability value) {
        minimumServability.set(value);
    }

    public Set<AnomalyType> anomalyTypes() {
        return anomalyTypes.get();
    }

    public Set<Flow> flows() {
        return flows.get();
    }

    public Servability minimumServability() {
        return minimumServability.get();
    }

    @Override
    public String toString() {
        return "AlertingSwitches[email=" + emailEnabled.get()
                + ", anomalies=" + anomalyTypes.get()
                + ", flows=" + flows.get()
                + ", minServability=" + minimumServability.get() + ']';
    }

    private static <E extends Enum<E>> Set<E> copyOf(Collection<E> values, Class<E> type) {
        EnumSet<E> set = EnumSet.noneOf(type);
        if (values != null) {
            set.addAll(values);
        }
        return set;
    }
}
