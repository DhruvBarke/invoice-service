package com.example.invoice.service.domain.port.out;

import com.example.invoice.service.domain.model.PartyRegistrationDetails;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable record of defects and their corrections. The system of record for data quality.
 *
 * <p>Implementations may block; called on the load path only, never on a cache hit. Callers degrade
 * to serving raw data when the store is unavailable — a database outage must not stop party lookups.
 */
public interface QuarantineStore {

    Optional<QuarantineRecord> findActive(String keySpace, String lookupKey);

    /**
     * Inserts a new row, or updates the existing active one.
     *
     * <p><b>Must be idempotent per fingerprint.</b> When the incoming fingerprint matches the stored
     * one, only {@code updated_at} changes and {@code notified_at} is preserved — that preservation
     * is exactly what stops a stable defect from notifying on every detection. When the fingerprint
     * differs the defect has genuinely changed, so {@code notified_at} clears and any correction is
     * discarded, because it was written against a value that no longer applies.
     */
    UpsertResult upsert(QuarantineRecord record);

    /** Records that a human has been told. Called only after notification has been handed off. */
    void markNotified(long id, Instant notifiedAt);

    /** Applies an operator correction. Takes effect on the next lookup. */
    QuarantineRecord applyCorrection(long id, List<PartyRegistrationDetails> corrected,
                                     String correctedBy, String notes);

    /**
     * Retires a row so the referential value flows again. If the defect is in fact still present,
     * detection re-fires and a fresh row opens with a new notification — correct behaviour for a
     * premature retirement, though it does surprise people.
     */
    void softDelete(long id, String deletedBy);

    /**
     * @return rows changed at or after {@code since}, ascending.
     *
     * <p>Drives cross-instance propagation. Without it, a correction is visible only to the instance
     * that received it until every other one's cache lifetime lapses — so "corrections apply
     * immediately" would silently mean "on one pod immediately, on the rest eventually".
     */
    List<QuarantineRecord> findChangedSince(Instant since, int limit);

    /** For an operator console. */
    List<QuarantineRecord> findByStatus(QuarantineStatus status, int limit);

    /** @param needsNotification true when this upsert produced a defect nobody has been told about. */
    record UpsertResult(QuarantineRecord record, boolean needsNotification) { }
}
