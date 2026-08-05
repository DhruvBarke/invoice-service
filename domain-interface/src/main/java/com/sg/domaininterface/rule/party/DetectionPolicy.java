package com.sg.domaininterface.rule.party;

import com.sg.domaininterface.model.party.Flow;

/**
 * Which advisory checks run, per flow.
 *
 * <p><b>The blocking checks are not configurable</b> — nothing returned, and a record with no
 * usable SIREN. Switching either off would not make the data usable: an empty referential answer
 * still has no party in it, and a record with no SIREN still cannot anchor an invoice. All it
 * would do is stop anyone being told, and the invoice would register against a party that does
 * not exist. Only the checks whose findings are {@link Servability#SERVABLE} are policy.
 *
 * <p><b>MULTIPLE_REGISTRATIONS is one of those.</b> It used to be lumped in with the blocking
 * pair as "mandatory", but it is advisory in the same sense as the other two: a golden record is
 * still selected deterministically (see {@link GoldenRecordSelector}), so the lookup has an
 * answer. What the anomaly reports is that upstream deduplication disagreed with itself and the
 * selection was a genuine choice rather than a collapse — worth recording where it matters, and
 * worth being able to quieten where duplicates are expected and understood.
 *
 * <p><b>Distinct from anything in the alerting module.</b> This governs whether an anomaly is
 * <em>detected</em>, which determines whether it is recorded and whether it blocks. Alerting's
 * own switches govern only whether an email is sent. Turning a check off here means no quarantine
 * row will ever exist for it, and no correction will ever be possible.
 *
 * <p>Built through {@link #builder()} rather than the canonical constructor. Six positional
 * booleans is how a configuration silently inverts — the compiler is perfectly happy with the
 * inbound and outbound flags the wrong way round, and the result is a check that runs on exactly
 * the flow it was meant to be off for.
 *
 * @param inboundMissingSiret           the inbound SIRET comes from the elementary party and is
 *                                      sometimes legitimately absent, so it is separable from the
 *                                      outbound check
 * @param outboundGoldenMismatch        off by default: an outbound lookup is often made with an
 *                                      elementary id deliberately, so the mismatch is expected
 *                                      rather than a defect
 * @param inboundMultipleRegistrations  several parties for one SIREN
 * @param outboundMultipleRegistrations several parties for one BDR id
 */
public record DetectionPolicy(
        boolean inboundMissingSiret,
        boolean outboundMissingSiret,
        boolean inboundGoldenMismatch,
        boolean outboundGoldenMismatch,
        boolean inboundMultipleRegistrations,
        boolean outboundMultipleRegistrations
) {

    /** Every advisory check on, except the outbound golden mismatch. */
    public static DetectionPolicy defaults() {
        return builder().build();
    }

    /**
     * Only the blocking checks run. Advisory findings are neither recorded nor reported.
     *
     * <p>Note this does not mean "nothing is detected": a party that cannot be served is still
     * detected and still blocks, because that is not something a policy can turn off.
     */
    public static DetectionPolicy mandatoryOnly() {
        return builder()
                .missingSiret(false, false)
                .goldenMismatch(false, false)
                .multipleRegistrations(false, false)
                .build();
    }

    public boolean checkMissingSiret(Flow flow) {
        return flow == Flow.INBOUND ? inboundMissingSiret : outboundMissingSiret;
    }

    public boolean checkGoldenMismatch(Flow flow) {
        return flow == Flow.INBOUND ? inboundGoldenMismatch : outboundGoldenMismatch;
    }

    public boolean checkMultipleRegistrations(Flow flow) {
        return flow == Flow.INBOUND ? inboundMultipleRegistrations : outboundMultipleRegistrations;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Names each flag at its call site.
     *
     * <p>Every setter takes the inbound value first and the outbound value second, consistently,
     * so the one thing a reader has to remember is the same in all three places.
     */
    public static final class Builder {
        private boolean inboundMissingSiret = true;
        private boolean outboundMissingSiret = true;
        private boolean inboundGoldenMismatch = true;
        // An outbound lookup is often made with an elementary id on purpose.
        private boolean outboundGoldenMismatch = false;
        private boolean inboundMultipleRegistrations = true;
        private boolean outboundMultipleRegistrations = true;

        private Builder() {}

        public Builder missingSiret(boolean inbound, boolean outbound) {
            this.inboundMissingSiret = inbound;
            this.outboundMissingSiret = outbound;
            return this;
        }

        public Builder goldenMismatch(boolean inbound, boolean outbound) {
            this.inboundGoldenMismatch = inbound;
            this.outboundGoldenMismatch = outbound;
            return this;
        }

        public Builder multipleRegistrations(boolean inbound, boolean outbound) {
            this.inboundMultipleRegistrations = inbound;
            this.outboundMultipleRegistrations = outbound;
            return this;
        }

        public DetectionPolicy build() {
            return new DetectionPolicy(
                    inboundMissingSiret, outboundMissingSiret,
                    inboundGoldenMismatch, outboundGoldenMismatch,
                    inboundMultipleRegistrations, outboundMultipleRegistrations);
        }
    }
}
