package com.sg.domain.party;

import com.sg.domaininterface.model.party.Flow;
import com.sg.domaininterface.model.party.KeySpace;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.model.party.RegistrationType;
import com.sg.domaininterface.rule.party.Anomaly;
import com.sg.domaininterface.rule.party.AnomalyType;
import com.sg.domaininterface.rule.party.DetectionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Inspects a referential response for defects.
 *
 * <p>Pure: no I/O, no state, no clock, no logging. Every rule here is a statement about what invoice
 * registration requires, and every one can be tested with a literal and an assertion.
 *
 * <p>Cheap enough to run on every load, which is what allows the healthy path to skip the quarantine
 * store entirely — a clean response never opens a database connection.
 */
public final class AnomalyDetector {

    private final DetectionPolicy policy;

    public AnomalyDetector(DetectionPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * @param response what the referential returned; empty or null when nothing was found
     * @return every defect found, in detection order; empty when the response is clean
     */
    public List<Anomaly> detect(Flow flow, KeySpace keySpace, String lookupKey,
                                List<PartyRegistrationDetails> response) {

        List<Anomaly> found = new ArrayList<>(2);

        if (response == null || response.isEmpty()) {
            // Nothing to inspect further: every other check needs a record to look at.
            found.add(Anomaly.of(AnomalyType.NO_REGISTRATION_FOUND,
                    "referential returned no registration details for " + keySpace + "=" + lookupKey));
            return found;
        }

        if (!keySpace.isMultiValued() && response.size() > 1) {
            found.add(Anomaly.of(AnomalyType.MULTIPLE_REGISTRATIONS,
                    keySpace + "=" + lookupKey + " is single-valued but returned "
                            + response.size() + " registration details",
                    response.get(0)));
        }

        for (PartyRegistrationDetails d : response) {
            // A malformed SIREN is as unusable as an absent one — it could never anchor an invoice
            // and could never be found by lookup — so both take the same path.
            if (RegistrationType.SIREN.normalize(d.siren()) == null) {
                found.add(Anomaly.of(AnomalyType.MISSING_SIREN,
                        "record " + d.goldenBdrId() + " has no usable SIREN"
                                + (d.siren() == null ? "" : " (value: " + d.siren() + ")"),
                        d));
            }

            if (policy.checkMissingSiret(flow)
                    && RegistrationType.SIRET.normalize(d.siret()) == null) {
                found.add(Anomaly.of(AnomalyType.MISSING_SIRET,
                        "record " + d.goldenBdrId() + " has no usable SIRET", d));
            }

            if (policy.checkGoldenMismatch(flow) && isGoldenMismatch(d)) {
                found.add(Anomaly.of(AnomalyType.GOLDEN_PARTY_MISMATCH,
                        "resolved party is a duplicate: elemBdrId=" + d.elemBdrId()
                                + " differs from goldenBdrId=" + d.goldenBdrId()
                                + "; invoice registration must use the golden details", d));
            }
        }
        return found;
    }

    private static boolean isGoldenMismatch(PartyRegistrationDetails d) {
        String elem = d.elemBdrId();
        return elem != null && !elem.isBlank() && !elem.equals(d.goldenBdrId());
    }
}
