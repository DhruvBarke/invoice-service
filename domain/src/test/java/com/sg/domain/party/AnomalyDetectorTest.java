package com.sg.domain.party;

import com.sg.domaininterface.model.party.Flow;
import com.sg.domaininterface.model.party.KeySpace;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.rule.party.Anomaly;
import com.sg.domaininterface.rule.party.AnomalyType;
import com.sg.domaininterface.rule.party.DetectionPolicy;
import com.sg.domaininterface.rule.party.Servability;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The point of these tests is what they do <em>not</em> need: no database, no mail server, no cache,
 * no Spring context. That is the payoff of moving detection into the domain — the business
 * invariants are checkable with literals and assertions.
 */
class AnomalyDetectorTest {

    private final AnomalyDetector detector = new AnomalyDetector(DetectionPolicy.defaults());

    private static PartyRegistrationDetails record(String golden, String elem,
                                                    String siren, String siret) {
        return new PartyRegistrationDetails(elem, "Office", "OFF", "TP-1", "Company", "CO",
                golden, "Office", "OFF", siren, siret, List.of());
    }

    @Test
    void cleanResponseYieldsNoAnomalies() {
        var clean = record("G1", "G1", "123456789", "12345678900012");
        assertTrue(detector.detect(Flow.INBOUND, KeySpace.SIREN, "123456789", List.of(clean))
                .isEmpty());
    }

    @Test
    void emptyResponseIsBlocking() {
        var found = detector.detect(Flow.INBOUND, KeySpace.SIREN, "123456789", List.of());
        assertEquals(1, found.size());
        assertEquals(AnomalyType.NO_REGISTRATION_FOUND, found.get(0).type());
        assertEquals(Servability.BLOCKING, Anomaly.servabilityOf(found));
    }

    @Test
    void missingSirenIsBlocking() {
        var bad = record("G1", "G1", null, "12345678900012");
        var found = detector.detect(Flow.OUTBOUND, KeySpace.BDR_ID, "G1", List.of(bad));
        assertTrue(found.stream().anyMatch(a -> a.type() == AnomalyType.MISSING_SIREN));
        assertEquals(Servability.BLOCKING, Anomaly.servabilityOf(found));
    }

    /** A SIREN that cannot be normalized is as unusable as an absent one. */
    @Test
    void malformedSirenIsTreatedAsMissing() {
        var bad = record("G1", "G1", "12345", "12345678900012");
        var found = detector.detect(Flow.OUTBOUND, KeySpace.BDR_ID, "G1", List.of(bad));
        assertTrue(found.stream().anyMatch(a -> a.type() == AnomalyType.MISSING_SIREN));
    }

    @Test
    void missingSiretIsServable() {
        var noSiret = record("G1", "G1", "123456789", null);
        var found = detector.detect(Flow.INBOUND, KeySpace.SIREN, "123456789", List.of(noSiret));
        assertTrue(found.stream().anyMatch(a -> a.type() == AnomalyType.MISSING_SIRET));
        assertEquals(Servability.SERVABLE, Anomaly.servabilityOf(found),
                "a missing SIRET must not stall invoicing");
    }

    @Test
    void severalRecordsForSirenIsAnAnomalyButSeveralForSiretIsNot() {
        var a = record("G1", "G1", "123456789", "12345678900012");
        var b = record("G2", "G2", "123456789", "12345678900037");

        assertTrue(detector.detect(Flow.INBOUND, KeySpace.SIREN, "123456789", List.of(a, b))
                .stream().anyMatch(x -> x.type() == AnomalyType.MULTIPLE_REGISTRATIONS));

        assertFalse(detector.detect(Flow.INBOUND, KeySpace.SIRET, "12345678900012", List.of(a, b))
                .stream().anyMatch(x -> x.type() == AnomalyType.MULTIPLE_REGISTRATIONS),
                "SIRET duplicates are expected, not a defect");
    }

    @Test
    void goldenMismatchIsDetectedInboundButNotOutboundByDefault() {
        var duplicate = record("G1", "ELEM-5", "123456789", "12345678900012");

        assertTrue(detector.detect(Flow.INBOUND, KeySpace.SIREN, "123456789", List.of(duplicate))
                .stream().anyMatch(x -> x.type() == AnomalyType.GOLDEN_PARTY_MISMATCH));

        assertFalse(detector.detect(Flow.OUTBOUND, KeySpace.BDR_ID, "ELEM-5", List.of(duplicate))
                .stream().anyMatch(x -> x.type() == AnomalyType.GOLDEN_PARTY_MISMATCH),
                "outbound lookups carry elementary ids deliberately");
    }

    @Test
    void oneBlockingDefectBlocksTheWholeResponse() {
        var bad = record("G1", "G1", null, null);   // missing SIREN (blocking) + SIRET (servable)
        assertEquals(Servability.BLOCKING,
                Anomaly.servabilityOf(detector.detect(Flow.INBOUND, KeySpace.SIREN, "1", List.of(bad))));
    }
}
