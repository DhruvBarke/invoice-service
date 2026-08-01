package com.example.invoice.service.domain.rule;

import com.example.invoice.service.domain.model.Flow;

/**
 * Which optional checks run, per flow.
 *
 * <p><b>The mandatory checks are not configurable</b> — nothing returned, missing SIREN, several
 * records for a single-valued key. Each makes the data unusable or ambiguous for invoice
 * registration, so allowing them to be switched off would mean allowing an invoice to be built on
 * data known to be unusable. Only the advisory checks are policy.
 *
 * <p><b>Distinct from anything in the alerting module.</b> This governs whether an anomaly is
 * <em>detected</em>, which determines whether it is recorded and whether it blocks. Alerting's own
 * switches govern only whether an email is sent. Turning a check off here means no quarantine row
 * will ever exist for it and no correction will ever be possible.
 *
 * @param inboundMissingSiret    the inbound SIRET comes from the elementary party and is sometimes
 *                               legitimately absent, so it is separable from the outbound check
 * @param outboundGoldenMismatch off by default: an outbound lookup is often made with an elementary
 *                               id deliberately, so the mismatch is expected rather than a defect
 */
public record DetectionPolicy(
        boolean inboundMissingSiret,
        boolean outboundMissingSiret,
        boolean inboundGoldenMismatch,
        boolean outboundGoldenMismatch
) {
    public static DetectionPolicy defaults() {
        return new DetectionPolicy(true, true, true, false);
    }

    /** Only the mandatory checks run. Advisory findings are neither recorded nor reported. */
    public static DetectionPolicy mandatoryOnly() {
        return new DetectionPolicy(false, false, false, false);
    }

    public boolean checkMissingSiret(Flow flow) {
        return flow == Flow.INBOUND ? inboundMissingSiret : outboundMissingSiret;
    }

    public boolean checkGoldenMismatch(Flow flow) {
        return flow == Flow.INBOUND ? inboundGoldenMismatch : outboundGoldenMismatch;
    }
}
