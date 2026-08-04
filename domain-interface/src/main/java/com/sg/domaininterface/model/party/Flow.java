package com.sg.domaininterface.model.party;



/**
 * Which direction a lookup serves.
 *
 * <p>A domain concept, not a plumbing one: the two flows have genuinely different expectations. An
 * inbound search starts from a registration number and the SIRET may legitimately be absent; an
 * outbound search starts from a BDR id, often an elementary one deliberately, so a golden mismatch
 * is expected rather than a defect. {@code DetectionPolicy} is keyed on this.
 */
public enum Flow {
    /** Search by SIREN or SIRET. */
    INBOUND,
    /** Search by BDR id. */
    OUTBOUND
}
