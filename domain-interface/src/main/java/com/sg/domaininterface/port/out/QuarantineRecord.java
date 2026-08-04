package com.sg.domaininterface.port.out;

import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.rule.party.AnomalyType;
import com.sg.domaininterface.rule.party.Servability;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A recorded defect and, once supplied, its correction.
 *
 * @param rawPayload       what the referential returned; {@code null} for
 *                         {@link AnomalyType#NO_REGISTRATION_FOUND}, where an operator supplies
 *                         everything from scratch
 * @param correctedPayload the operator-supplied replacement, served in preference to the referential
 * @param fingerprint      identity of "this exact defect"
 * @param notifiedAt       {@code null} until a human has been told; the sole gate on notification
 */
public record QuarantineRecord(
        Long id,
        String keySpace,
        String lookupKey,
        String fingerprint,
        Set<AnomalyType> anomalyTypes,
        Servability servability,
        List<PartyRegistrationDetails> rawPayload,
        List<PartyRegistrationDetails> correctedPayload,
        QuarantineStatus status,
        Instant detectedAt,
        Instant updatedAt,
        Instant notifiedAt,
        String correctedBy,
        String notes
) {
    public QuarantineRecord {
        Objects.requireNonNull(keySpace, "keySpace");
        Objects.requireNonNull(lookupKey, "lookupKey");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(status, "status");
        anomalyTypes = anomalyTypes == null ? Set.of() : Set.copyOf(anomalyTypes);
        rawPayload = rawPayload == null ? null : List.copyOf(rawPayload);
        correctedPayload = correctedPayload == null ? null : List.copyOf(correctedPayload);
    }

    public boolean hasUsableCorrection() {
        return status == QuarantineStatus.CORRECTED
                && correctedPayload != null && !correctedPayload.isEmpty();
    }

    public boolean alreadyNotified() {
        return notifiedAt != null;
    }

    public String reference() {
        return id == null ? null : id.toString();
    }

    /**
     * Builds the defect identity: sorted anomaly types plus a hash of the fields those anomalies
     * concern.
     *
     * <p>Two properties fall out of this, both load-bearing. A defect that recurs unchanged keeps its
     * fingerprint, so it is reported once however often it is hit. A defect whose content
     * <em>changes</em> gets a new fingerprint and is reported again — a different bad value is a
     * different problem, and any correction written against the old value is invalidated.
     *
     * <p>Stable across restarts and across instances, which is what lets "notify once" hold in a
     * multi-pod deployment rather than degrading to once per pod.
     */
    public static String fingerprintOf(String keySpace, String lookupKey,
                                       Set<AnomalyType> types, List<PartyRegistrationDetails> raw) {
        StringBuilder sb = new StringBuilder(keySpace).append(':').append(lookupKey).append(':');
        for (AnomalyType t : new TreeSet<>(types)) {
            sb.append(t.name()).append(',');
        }
        sb.append('#').append(contentHash(raw));
        return sb.length() <= 128 ? sb.toString() : sb.substring(0, 128);
    }

    private static int contentHash(List<PartyRegistrationDetails> raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        int h = 1;
        for (PartyRegistrationDetails d : raw) {
            // Only the fields the anomalies concern. Cosmetic churn elsewhere — a renamed office —
            // must not look like a new defect and re-notify.
            h = 31 * h + Objects.hash(d.goldenBdrId(), d.elemBdrId(), d.siren(), d.siret());
        }
        return h;
    }
}
