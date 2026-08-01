package com.example.invoice.service.domain.port.in;

/**
 * Raised when registration details cannot be served.
 *
 * <p>{@code referenceId} carries an adapter-supplied handle — typically a quarantine row id — that an
 * operator can quote. It is an opaque string precisely so the domain needs no knowledge of what
 * produced it. Surface it rather than a bare failure: it is the difference between "invoice
 * rejected" and "invoice rejected, fix row 4471".
 */
public class PartyRegistrationUnavailableException extends RuntimeException {

    private final UnavailabilityReason reason;
    private final String keySpace;
    private final String lookupKey;
    private final String referenceId;

    public PartyRegistrationUnavailableException(UnavailabilityReason reason, String keySpace,
                                                 String lookupKey, String message) {
        this(reason, keySpace, lookupKey, message, null, null);
    }

    public PartyRegistrationUnavailableException(UnavailabilityReason reason, String keySpace,
                                                 String lookupKey, String message,
                                                 String referenceId, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.keySpace = keySpace;
        this.lookupKey = lookupKey;
        this.referenceId = referenceId;
    }

    public UnavailabilityReason reason() {
        return reason;
    }

    /** One of {@code SIREN}, {@code SIRET}, {@code BDR_ID}. */
    public String keySpace() {
        return keySpace;
    }

    public String lookupKey() {
        return lookupKey;
    }

    /** @return an operator-quotable handle, or {@code null}. */
    public String referenceId() {
        return referenceId;
    }

    public boolean isRetryable() {
        return reason != null && reason.isRetryable();
    }
}
