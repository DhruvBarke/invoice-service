package com.sg.domaininterface.port.out;

/**
 * Why a lookup could not be served, at a granularity a consumer can act on.
 *
 * <p>The {@code retryable} flag is the point: a mapper needs to distinguish "try again" from "a
 * human must act", and nothing more. Which subsystem produced the failure is deliberately not
 * exposed.
 */
public enum UnavailabilityReason {

    /** No such party. Not an error condition in itself. */
    NOT_FOUND(false),

    /**
     * The data exists but is blocked by a domain rule, with no correction supplied. Retrying will
     * not help; an operator must supply a correction or fix the source.
     */
    BLOCKED(false),

    /** The referential could not be reached. Retrying may succeed. */
    UPSTREAM_UNAVAILABLE(true),

    /** The supplied identifier could not be parsed as a valid key. */
    INVALID_IDENTIFIER(false);

    private final boolean retryable;

    UnavailabilityReason(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
