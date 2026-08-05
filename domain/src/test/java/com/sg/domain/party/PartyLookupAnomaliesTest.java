package com.sg.domain.party;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domaininterface.model.party.Flow;
import com.sg.domaininterface.model.party.KeySpace;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.rule.party.Anomaly;
import com.sg.domaininterface.rule.party.AnomalyType;
import com.sg.domaininterface.rule.party.DetectionPolicy;
import com.sg.domaininterface.rule.party.Servability;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The four ways a party lookup comes back unusable, one per flow and cardinality.
 *
 * <p>Inbound resolves a SIREN off the invoice; outbound resolves an SG BDR id. Each can return
 * nothing, or return more than one party — and outbound can additionally return a party carrying
 * no SIREN, which is the field the invoice is ultimately anchored on.
 *
 * <p>Written as one file per scenario rather than folded into the detector's own tests because
 * these are the cases an operator asks about by name. Each also asserts what the policy can and
 * cannot switch off, since that distinction is the whole reason two of them are blocking.
 */
class PartyLookupAnomaliesTest {

    private static PartyRegistrationDetails party(String golden, String elem,
                                                  String siren, String siret) {
        return new PartyRegistrationDetails(elem, "Office", "OFF", "TP-1", "Company", "CO",
                golden, "Office", "OFF", siren, siret, List.of());
    }

    private static AnomalyDetector detector() {
        return new AnomalyDetector(DetectionPolicy.defaults());
    }

    private static List<AnomalyType> typesOf(List<Anomaly> anomalies) {
        return anomalies.stream().map(Anomaly::type).toList();
    }

    // ── Inbound: by SIREN ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("inbound, resolving a SIREN")
    class Inbound {

        @Test
        @DisplayName("no registration at all blocks, and nothing else is inspected")
        void noRegistrationBlocks() {
            List<Anomaly> nulled = detector()
                    .detect(Flow.INBOUND, KeySpace.SIREN, "123456789", null);
            List<Anomaly> empty = detector()
                    .detect(Flow.INBOUND, KeySpace.SIREN, "123456789", List.of());

            assertEquals(List.of(AnomalyType.NO_REGISTRATION_FOUND), typesOf(nulled));
            assertEquals(List.of(AnomalyType.NO_REGISTRATION_FOUND), typesOf(empty),
                    "a null response and an empty one are the same absence");

            // Every other check needs a record to look at, so reporting them here would be
            // reporting on nothing.
            assertEquals(Servability.BLOCKING, Anomaly.servabilityOf(empty));
            assertTrue(empty.get(0).detail().contains("123456789"),
                    "the detail names the key, so the alert points at a specific invoice");
        }

        @Test
        @DisplayName("more than one party for one SIREN is reported but still servable")
        void multipleRegistrationsAreAdvisory() {
            List<Anomaly> found = detector().detect(Flow.INBOUND, KeySpace.SIREN, "123456789",
                    List.of(party("G1", "G1", "123456789", "12345678900012"),
                            party("G2", "G2", "123456789", "12345678900013")));

            assertTrue(typesOf(found).contains(AnomalyType.MULTIPLE_REGISTRATIONS));
            // A golden record is still selected deterministically, so the lookup has an answer.
            // What this records is that upstream deduplication disagreed with itself.
            assertEquals(Servability.SERVABLE, Anomaly.servabilityOf(found));
            assertTrue(found.get(0).detail().contains("2"), "the count is in the detail");
        }

        @Test
        @DisplayName("the multiple-registrations check can be switched off for this flow alone")
        void multipleRegistrationsIsConfigurable() {
            AnomalyDetector inboundOff = new AnomalyDetector(DetectionPolicy.builder()
                    .multipleRegistrations(false, true)
                    .build());

            List<PartyRegistrationDetails> duplicates =
                    List.of(party("G1", "G1", "123456789", "12345678900012"),
                            party("G2", "G2", "123456789", "12345678900013"));

            assertFalse(typesOf(inboundOff.detect(Flow.INBOUND, KeySpace.SIREN, "123456789",
                    duplicates)).contains(AnomalyType.MULTIPLE_REGISTRATIONS));
            assertTrue(typesOf(inboundOff.detect(Flow.OUTBOUND, KeySpace.BDR_ID, "G1",
                    duplicates)).contains(AnomalyType.MULTIPLE_REGISTRATIONS),
                    "switching a flow off must not switch the other one off with it");
        }
    }

    // ── Outbound: by BDR id ───────────────────────────────────────────────────

    @Nested
    @DisplayName("outbound, resolving a BDR id")
    class Outbound {

        @Test
        @DisplayName("no registration for the BDR id blocks")
        void noRegistrationBlocks() {
            List<Anomaly> found =
                    detector().detect(Flow.OUTBOUND, KeySpace.BDR_ID, "G-UNKNOWN", List.of());

            assertEquals(List.of(AnomalyType.NO_REGISTRATION_FOUND), typesOf(found));
            assertEquals(Servability.BLOCKING, Anomaly.servabilityOf(found));
        }

        @Test
        @DisplayName("a party with no usable SIREN blocks, whatever else it carries")
        void missingSirenBlocks() {
            // The SIREN is what an invoice is anchored on. A party without one cannot be used
            // even though the referential answered, which is why this outranks the advisory
            // findings on the same record.
            List<Anomaly> found = detector().detect(Flow.OUTBOUND, KeySpace.BDR_ID, "G1",
                    List.of(party("G1", "G1", null, "12345678900012")));

            assertTrue(typesOf(found).contains(AnomalyType.MISSING_SIREN));
            assertEquals(Servability.BLOCKING, Anomaly.servabilityOf(found));
        }

        @Test
        @DisplayName("a malformed SIREN blocks the same way an absent one does")
        void malformedSirenBlocks() {
            // Nine digits or it could never anchor an invoice and could never be found by
            // lookup. "Present but unusable" is not a softer failure than "absent".
            List<Anomaly> found = detector().detect(Flow.OUTBOUND, KeySpace.BDR_ID, "G1",
                    List.of(party("G1", "G1", "12345", "12345678900012")));

            assertTrue(typesOf(found).contains(AnomalyType.MISSING_SIREN));
            assertTrue(found.get(0).detail().contains("12345"),
                    "the offending value is quoted, so nobody has to go and look it up");
        }

        @Test
        @DisplayName("more than one party for one BDR id is reported but still servable")
        void multipleRegistrationsAreAdvisory() {
            List<Anomaly> found = detector().detect(Flow.OUTBOUND, KeySpace.BDR_ID, "G1",
                    List.of(party("G1", "G1", "123456789", "12345678900012"),
                            party("G1", "E9", "123456789", "12345678900013")));

            assertTrue(typesOf(found).contains(AnomalyType.MULTIPLE_REGISTRATIONS));
            assertEquals(Servability.SERVABLE, Anomaly.servabilityOf(found));
        }
    }

    // ── What the policy may not do ────────────────────────────────────────────

    @Test
    @DisplayName("the blocking checks run even when every advisory check is off")
    void blockingChecksSurviveMandatoryOnly() {
        AnomalyDetector minimal = new AnomalyDetector(DetectionPolicy.mandatoryOnly());

        // Switching detection off would not make the data usable — an empty answer still has no
        // party in it, and a record with no SIREN still cannot anchor an invoice. All it would
        // do is stop anyone being told, and the invoice would register against a party that
        // does not exist. So these two are deliberately not policy.
        assertEquals(List.of(AnomalyType.NO_REGISTRATION_FOUND),
                typesOf(minimal.detect(Flow.INBOUND, KeySpace.SIREN, "123456789", List.of())));

        assertEquals(List.of(AnomalyType.MISSING_SIREN),
                typesOf(minimal.detect(Flow.OUTBOUND, KeySpace.BDR_ID, "G1",
                        List.of(party("G1", "G1", null, null)))),
                "and the advisory findings on the same record are silent, as configured");
    }

    @Test
    @DisplayName("a clean response yields nothing")
    void cleanResponseIsSilent() {
        assertTrue(detector().detect(Flow.INBOUND, KeySpace.SIREN, "123456789",
                List.of(party("G1", "G1", "123456789", "12345678900012"))).isEmpty());
    }
}
