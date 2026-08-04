package com.sg.domain.quarantine;

import com.sg.domain.quarantine.QuarantineService;
import com.sg.domaininterface.model.party.Flow;
import com.sg.domaininterface.model.party.KeySpace;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.port.out.GuardDecision;
import com.sg.domaininterface.port.out.ResponseGuard;
import com.sg.domaininterface.rule.party.Anomaly;
import com.sg.domain.party.AnomalyDetector;
import java.util.List;
import java.util.Objects;

/**
 * The adapter: runs the domain's detection rules, records what they find, and translates the outcome
 * into a {@link GuardDecision}.
 *
 * <p><b>Clean responses cost nothing.</b> Detection is pure and in-memory; only an anomalous response
 * touches the database. A healthy hot path never opens a connection.
 */
public final class QuarantiningResponseGuard implements ResponseGuard {

    private final AnomalyDetector detector;
    private final QuarantineService quarantine;
    private final boolean autoRetireResolved;

    public QuarantiningResponseGuard(AnomalyDetector detector, QuarantineService quarantine,
                                     boolean autoRetireResolved) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.quarantine = Objects.requireNonNull(quarantine, "quarantine");
        this.autoRetireResolved = autoRetireResolved;
    }

    @Override
    public GuardDecision inspect(Flow flow, KeySpace keySpace, String lookupKey,
                                 List<PartyRegistrationDetails> response) {

        List<Anomaly> anomalies = detector.detect(flow, keySpace, lookupKey, response);

        if (anomalies.isEmpty()) {
            if (autoRetireResolved) {
                // A previously-defective key is clean again. Retire the row so a stale correction
                // stops shadowing data that has since been fixed at source.
                quarantine.retireIfResolved(keySpace.name(), lookupKey);
            }
            return GuardDecision.pass(response);
        }

        QuarantineService.Verdict verdict =
                quarantine.handle(flow, keySpace.name(), lookupKey, response, anomalies);

        if (verdict.blocked()) {
            return GuardDecision.block(verdict.referenceId());
        }
        // Corrected and defect-carrying data both get the short lifetime: the first so a later
        // correction or retirement lands quickly, the second so an upstream fix is picked up without
        // waiting a full cache lifetime.
        return GuardDecision.serveVolatile(verdict.records(), verdict.referenceId());
    }
}
