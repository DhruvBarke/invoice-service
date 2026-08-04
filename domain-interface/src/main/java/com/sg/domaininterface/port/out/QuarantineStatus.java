package com.sg.domaininterface.port.out;

public enum QuarantineStatus {
    /** Detected, no correction supplied. Blocking defects stay blocked. */
    PENDING,
    /** An operator supplied a replacement; it takes precedence over the referential. */
    CORRECTED,
    /** Upstream source fixed; the row is inert and the referential value flows again. */
    SOFT_DELETED
}
