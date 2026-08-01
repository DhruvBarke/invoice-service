package com.example.invoice.service.alerting.quarantine;

import com.example.invoice.service.domain.model.Flow;
import com.example.invoice.service.domain.model.PartyRegistrationDetails;
import com.example.invoice.service.domain.model.RegistrationType;
import com.example.invoice.service.domain.port.out.AlertNotifier;
import com.example.invoice.service.domain.port.out.QuarantineRecord;
import com.example.invoice.service.domain.port.out.QuarantineStatus;
import com.example.invoice.service.domain.port.out.QuarantineStore;
import com.example.invoice.service.domain.rule.Anomaly;
import com.example.invoice.service.domain.rule.AnomalyType;
import com.example.invoice.service.domain.rule.Servability;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * Records defects, serves corrections, and decides who gets told.
 *
 * <p><b>Nothing here consults the alerting switches.</b> Blocking is decided from the domain's
 * servability alone, so no notification setting can influence whether unusable data is served.
 */
public final class QuarantineService {

    private static final System.Logger LOG = System.getLogger(QuarantineService.class.getName());

    private final QuarantineStore store;
    private final AlertNotifier notifier;

    private final LongAdder detected = new LongAdder();
    private final LongAdder correctionsServed = new LongAdder();
    private final LongAdder blocked = new LongAdder();
    private final LongAdder storeFailures = new LongAdder();
    private final LongAdder notifications = new LongAdder();

    public QuarantineService(QuarantineStore store, AlertNotifier notifier) {
        this.store = Objects.requireNonNull(store, "store");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
    }

    /** What the guard should do with this response. */
    public record Verdict(List<PartyRegistrationDetails> records, boolean blocked,
                          boolean corrected, String referenceId) { }

    public Verdict handle(Flow flow, String keySpace, String lookupKey,
                          List<PartyRegistrationDetails> response, List<Anomaly> anomalies) {

        detected.increment();
        Set<AnomalyType> types = EnumSet.noneOf(AnomalyType.class);
        for (Anomaly a : anomalies) {
            types.add(a.type());
        }
        Servability servability = Anomaly.servabilityOf(anomalies);
        boolean blocking = servability == Servability.BLOCKING;

        QuarantineRecord persisted;
        boolean needsNotification;
        try {
            Optional<QuarantineRecord> existing = store.findActive(keySpace, lookupKey);

            // A correction outranks the referential. Serve it and skip notification entirely: the
            // defect is already known and already handled.
            if (existing.isPresent() && existing.get().hasUsableCorrection()) {
                QuarantineRecord row = existing.get();
                List<PartyRegistrationDetails> corrected = validateCorrection(row);
                if (corrected != null) {
                    correctionsServed.increment();
                    return new Verdict(corrected, false, true, row.reference());
                }
                // The correction is itself unusable; fall through and treat the row as uncorrected.
            }

            QuarantineStore.UpsertResult result = store.upsert(new QuarantineRecord(
                    existing.map(QuarantineRecord::id).orElse(null),
                    keySpace, lookupKey,
                    QuarantineRecord.fingerprintOf(keySpace, lookupKey, types, response),
                    types, servability,
                    (response == null || response.isEmpty()) ? null : response,
                    null, QuarantineStatus.PENDING,
                    existing.map(QuarantineRecord::detectedAt).orElseGet(Instant::now),
                    Instant.now(),
                    existing.map(QuarantineRecord::notifiedAt).orElse(null),
                    null, null));

            persisted = result.record();
            needsNotification = result.needsNotification();

        } catch (RuntimeException e) {
            // Availability over bookkeeping: serve what we have, record nothing, notify anyway so
            // the defect is not lost entirely.
            storeFailures.increment();
            LOG.log(Level.ERROR, "Quarantine store unavailable for " + keySpace + "=" + lookupKey
                    + "; serving referential data unrecorded", e);
            publish(flow, keySpace, lookupKey, anomalies, servability, null);
            return new Verdict(blocking ? List.of() : response, blocking, false, null);
        }

        if (needsNotification) {
            publish(flow, keySpace, lookupKey, anomalies, servability, persisted);
            markNotified(persisted);
        }
        // else: already recorded and already reported. Repeats are silent by design.

        if (blocking) {
            blocked.increment();
            return new Verdict(List.of(), true, false, persisted.reference());
        }
        return new Verdict(response, false, false, persisted.reference());
    }

    /**
     * Retires a row whose defect has disappeared upstream.
     *
     * <p>Without this, a correction written months ago would keep shadowing a since-fixed referential
     * value indefinitely. It does mean a correction can be retired without a human, which is a
     * deliberate trade: stale overrides are the more likely failure.
     */
    public void retireIfResolved(String keySpace, String lookupKey) {
        try {
            store.findActive(keySpace, lookupKey).ifPresent(row -> {
                if (row.id() != null) {
                    store.softDelete(row.id(), "auto:upstream-resolved");
                    LOG.log(Level.INFO,
                            "Quarantine row {0} ({1}={2}) auto-retired: response is now clean",
                            row.id(), keySpace, lookupKey);
                }
            });
        } catch (RuntimeException e) {
            LOG.log(Level.DEBUG, "Could not check for an obsolete quarantine row", e);
        }
    }

    /**
     * Rejects a correction that is itself unusable, keeping the row blocked. See the package
     * documentation for why this check exists.
     */
    private List<PartyRegistrationDetails> validateCorrection(QuarantineRecord row) {
        for (PartyRegistrationDetails d : row.correctedPayload()) {
            if (RegistrationType.SIREN.normalize(d.siren()) == null) {
                LOG.log(Level.ERROR, "Quarantine row {0} ({1}={2}) has a correction with no usable "
                                + "SIREN; ignoring it and keeping the row blocked",
                        row.id(), row.keySpace(), row.lookupKey());
                return null;
            }
        }
        return row.correctedPayload();
    }

    private void publish(Flow flow, String keySpace, String lookupKey, List<Anomaly> anomalies,
                         Servability servability, QuarantineRecord row) {
        notifications.increment();

        StringBuilder details = new StringBuilder();
        StringBuilder types = new StringBuilder();
        List<PartyRegistrationDetails> samples = new ArrayList<>(2);
        for (Anomaly a : anomalies) {
            if (details.length() > 0) {
                details.append("; ");
                types.append(',');
            }
            details.append(a.detail());
            types.append(a.type().name());
            if (a.subject() != null && samples.size() < 3) {
                samples.add(a.subject());
            }
        }

        Map<String, String> context = new LinkedHashMap<>();
        context.put("flow", flow.name());
        context.put("keySpace", keySpace);
        context.put("lookupKey", lookupKey);
        context.put("anomalies", types.toString());
        context.put("quarantineRowId", (row == null || row.reference() == null)
                ? "NOT_PERSISTED" : row.reference());
        context.put("action", servability == Servability.BLOCKING
                ? "BLOCKED until a correction is supplied" : "served; correction optional");

        Set<AnomalyType> allTypes = EnumSet.noneOf(AnomalyType.class);
        for (Anomaly a : anomalies) {
            allTypes.add(a.type());
        }

        SafeNotify.publish(notifier, new AlertNotifier.Notification(
                anomalies.get(0).type(), servability, flow,
                row != null ? row.fingerprint()
                        : QuarantineRecord.fingerprintOf(keySpace, lookupKey, allTypes, null),
                details.toString(), Instant.now(), context, samples));
    }

    private void markNotified(QuarantineRecord row) {
        if (row == null || row.id() == null) {
            return;
        }
        try {
            store.markNotified(row.id(), Instant.now());
        } catch (RuntimeException e) {
            // Worst case the defect is reported twice — acceptable, unlike failing the lookup.
            LOG.log(Level.WARNING, "Could not mark quarantine row " + row.id() + " as notified", e);
        }
    }

    public QuarantineStats stats() {
        return new QuarantineStats(detected.sum(), correctionsServed.sum(), blocked.sum(),
                storeFailures.sum(), notifications.sum());
    }

    public record QuarantineStats(long detected, long correctionsServed, long blocked,
                                  long storeFailures, long notifications) { }
}
