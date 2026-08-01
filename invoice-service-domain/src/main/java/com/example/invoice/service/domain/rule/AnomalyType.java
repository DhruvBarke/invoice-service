package com.example.invoice.service.domain.rule;

/**
 * A defect detectable in a referential response.
 *
 * <p><b>Servability is a property of the defect, declared here.</b> That is what stops the two
 * categories being confused at a call site. A blocking defect withholds the record; a servable one
 * records the problem and lets processing continue. Getting this backwards in either direction is
 * costly: treat a missing SIRET as blocking and invoicing stalls on a cosmetic gap; treat a missing
 * SIREN as servable and unusable records reach registration silently.
 */
public enum AnomalyType {

    /**
     * The referential returned nothing. Recorded with a null payload so an operator can supply the
     * whole record — this is the case where the correction workflow earns the most, since the lookup
     * is otherwise a dead end.
     */
    NO_REGISTRATION_FOUND(Servability.BLOCKING),

    /** No usable SIREN. The company anchor is mandatory; registration cannot proceed without it. */
    MISSING_SIREN(Servability.BLOCKING),

    /**
     * A single-valued key resolved to several records. Servable — {@code GoldenRecordSelector} picks
     * deterministically — but a referential-integrity defect worth reporting.
     */
    MULTIPLE_REGISTRATIONS(Servability.SERVABLE),

    /** No SIRET. Servable; the office-level number is simply absent downstream. */
    MISSING_SIRET(Servability.SERVABLE),

    /**
     * {@code elemBdrId} differs from {@code goldenBdrId}: the resolved party is a duplicate, so
     * registration must use the golden details rather than the elementary ones. Servable, because
     * the golden fields are present on the record.
     */
    GOLDEN_PARTY_MISMATCH(Servability.SERVABLE);

    private final Servability servability;

    AnomalyType(Servability servability) {
        this.servability = servability;
    }

    public Servability servability() {
        return servability;
    }

    public boolean isBlocking() {
        return servability == Servability.BLOCKING;
    }
}
