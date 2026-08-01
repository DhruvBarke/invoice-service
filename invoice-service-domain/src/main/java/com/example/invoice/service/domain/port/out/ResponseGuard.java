package com.example.invoice.service.domain.port.out;

import com.example.invoice.service.domain.model.Flow;
import com.example.invoice.service.domain.model.KeySpace;
import com.example.invoice.service.domain.model.PartyRegistrationDetails;
import java.util.List;

/**
 * Decides what to do with a referential response before it is served.
 *
 * <p><b>Called on the load path only</b>, never on a cache hit, so an implementation may perform
 * I/O. A healthy hot path therefore pays nothing for data-quality handling.
 *
 * <p><b>Must not throw.</b> An implementation that fails should return {@link GuardDecision#pass}.
 * Callers wrap invocations defensively regardless: a guard failure must degrade data-quality
 * tracking, not availability of the lookup.
 */
@FunctionalInterface
public interface ResponseGuard {

    /**
     * @param response what the referential returned; empty when nothing was found
     * @return the verdict; never {@code null}
     */
    GuardDecision inspect(Flow flow, KeySpace keySpace, String lookupKey,
                          List<PartyRegistrationDetails> response);

    /**
     * Every response served exactly as the referential returned it: no detection, no recording, no
     * blocking, no notification.
     *
     * <p>A complete implementation, not a stub. Correct whenever another layer owns data quality, or
     * for a batch job or test harness that should not require a database.
     */
    static ResponseGuard passThrough() {
        return (flow, keySpace, lookupKey, response) -> GuardDecision.pass(response);
    }
}
