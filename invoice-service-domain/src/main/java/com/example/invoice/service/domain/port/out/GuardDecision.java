package com.example.invoice.service.domain.port.out;

import com.example.invoice.service.domain.model.PartyRegistrationDetails;
import java.util.List;

/**
 * A guard's verdict on a referential response.
 *
 * @param records     what to serve. May differ from the referential's answer when the guard supplies
 *                    an operator correction.
 * @param blocked     nothing may be served; the caller raises an unavailability failure
 * @param volatileTtl this result may be superseded out-of-band at any moment — by a correction, a
 *                    soft-delete, or an upstream fix — so it should be held only briefly regardless
 *                    of the normal cache lifetime
 * @param referenceId an operator-quotable handle, or {@code null}
 */
public record GuardDecision(
        List<PartyRegistrationDetails> records,
        boolean blocked,
        boolean volatileTtl,
        String referenceId
) {
    public GuardDecision {
        records = records == null ? List.of() : List.copyOf(records);
    }

    /** Serve the referential's answer unchanged, at the normal lifetime. */
    public static GuardDecision pass(List<PartyRegistrationDetails> records) {
        return new GuardDecision(records, false, false, null);
    }

    /** Serve these records, but hold them only briefly. */
    public static GuardDecision serveVolatile(List<PartyRegistrationDetails> records, String ref) {
        return new GuardDecision(records, false, true, ref);
    }

    public static GuardDecision block(String referenceId) {
        return new GuardDecision(List.of(), true, true, referenceId);
    }
}
