package com.sg.domain.party;

import com.sg.domaininterface.model.party.Flow;
import com.sg.domaininterface.model.party.KeySpace;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.port.out.GuardDecision;
import com.sg.domaininterface.port.out.PartyRegistrationUnavailableException;
import com.sg.domaininterface.port.out.UnavailabilityReason;
import com.sg.domaininterface.rule.party.Anomaly;
import com.sg.domaininterface.rule.party.AnomalyType;
import com.sg.domaininterface.rule.party.DetectionPolicy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The edge cases the primary rule tests don't reach: null inputs, single-element short
 * circuits, and the flow-dependent policy branches.
 */
class DomainRuleEdgeCasesTest {

  private static PartyRegistrationDetails party(String elemBdrId, String goldenBdrId,
                                                String siren, String siret) {
    return new PartyRegistrationDetails(elemBdrId, "name", "MNE", "TP", "tpName", "TPM",
        goldenBdrId, "Acme SA", "ACME", siren, siret, List.of());
  }

  // ── DetectionPolicy ───────────────────────────────────────────────────────

  @Nested
  @DisplayName("DetectionPolicy")
  class Policy {

    @Test
    @DisplayName("defaults check SIRET both ways but golden mismatch inbound only")
    void defaultsAreAsymmetric() {
      DetectionPolicy p = DetectionPolicy.defaults();
      assertTrue(p.checkMissingSiret(Flow.INBOUND));
      assertTrue(p.checkMissingSiret(Flow.OUTBOUND));
      assertTrue(p.checkGoldenMismatch(Flow.INBOUND));
      assertFalse(p.checkGoldenMismatch(Flow.OUTBOUND),
          "an outbound lookup often carries an elementary id deliberately");
    }

    @Test
    @DisplayName("mandatoryOnly switches every advisory check off")
    void mandatoryOnlyDisablesAdvisory() {
      DetectionPolicy p = DetectionPolicy.mandatoryOnly();
      assertFalse(p.checkMissingSiret(Flow.INBOUND));
      assertFalse(p.checkMissingSiret(Flow.OUTBOUND));
      assertFalse(p.checkGoldenMismatch(Flow.INBOUND));
      assertFalse(p.checkGoldenMismatch(Flow.OUTBOUND));
    }

    @Test
    @DisplayName("each flag is addressed by its own flow")
    void eachFlagIsFlowScoped() {
      DetectionPolicy p = new DetectionPolicy(true, false, false, true);
      assertTrue(p.checkMissingSiret(Flow.INBOUND));
      assertFalse(p.checkMissingSiret(Flow.OUTBOUND));
      assertFalse(p.checkGoldenMismatch(Flow.INBOUND));
      assertTrue(p.checkGoldenMismatch(Flow.OUTBOUND));
    }
  }

  // ── AnomalyDetector remaining branches ────────────────────────────────────

  @Nested
  @DisplayName("AnomalyDetector edge cases")
  class Detection {

    private final AnomalyDetector detector = new AnomalyDetector(DetectionPolicy.defaults());

    private static boolean hasType(List<Anomaly> found, AnomalyType type) {
      return found.stream().anyMatch(a -> a.type() == type);
    }

    @Test
    @DisplayName("a null response is treated the same as an empty one")
    void nullResponseIsNothingFound() {
      assertTrue(hasType(detector.detect(Flow.INBOUND, KeySpace.SIREN, "123456789", null),
          AnomalyType.NO_REGISTRATION_FOUND));
    }

    @Test
    @DisplayName("an empty response reports nothing found")
    void emptyResponseIsNothingFound() {
      assertTrue(hasType(detector.detect(Flow.INBOUND, KeySpace.SIREN, "123456789", List.of()),
          AnomalyType.NO_REGISTRATION_FOUND));
    }

    @Test
    @DisplayName("a blank SIREN is as missing as a null one")
    void blankSirenIsMissing() {
      assertTrue(hasType(detector.detect(Flow.INBOUND, KeySpace.SIREN, "k",
          List.of(party("E1", "G1", "   ", "12345678900012"))), AnomalyType.MISSING_SIREN));
      assertTrue(hasType(detector.detect(Flow.INBOUND, KeySpace.SIREN, "k",
          List.of(party("E1", "G1", null, "12345678900012"))), AnomalyType.MISSING_SIREN));
    }

    @Test
    @DisplayName("a blank SIRET is as missing as a null one, inbound")
    void blankSiretIsMissing() {
      assertTrue(hasType(detector.detect(Flow.INBOUND, KeySpace.SIREN, "k",
          List.of(party("E1", "G1", "123456789", "  "))), AnomalyType.MISSING_SIRET));
    }

    @Test
    @DisplayName("several records for a single-valued key is a defect")
    void multipleRecordsIsADefect() {
      assertTrue(hasType(detector.detect(Flow.INBOUND, KeySpace.SIREN, "k", List.of(
          party("E1", "G1", "123456789", "12345678900012"),
          party("E2", "G2", "123456789", "12345678900013"))),
          AnomalyType.MULTIPLE_REGISTRATIONS));
    }

    @Test
    @DisplayName("a clean single record yields nothing")
    void cleanRecordYieldsNothing() {
      assertTrue(detector.detect(Flow.INBOUND, KeySpace.SIREN, "k",
          List.of(party("G1", "G1", "123456789", "12345678900012"))).isEmpty());
    }

    @Test
    @DisplayName("with the SIRET check disabled, an absent SIRET is not reported")
    void siretCheckCanBeSwitchedOff() {
      AnomalyDetector lenient = new AnomalyDetector(DetectionPolicy.mandatoryOnly());
      assertFalse(hasType(lenient.detect(Flow.INBOUND, KeySpace.SIREN, "k",
          List.of(party("G1", "G1", "123456789", null))), AnomalyType.MISSING_SIRET),
          "switching the policy off means no quarantine row will ever exist for this");
    }

    @Test
    @DisplayName("a blank elemBdrId is not a golden mismatch — it means 'no duplicate'")
    void blankElemBdrIdIsNotAMismatch() {
      assertFalse(hasType(detector.detect(Flow.INBOUND, KeySpace.SIREN, "k",
          List.of(party("", "G1", "123456789", "12345678900012"))),
          AnomalyType.GOLDEN_PARTY_MISMATCH));
      assertFalse(hasType(detector.detect(Flow.INBOUND, KeySpace.SIREN, "k",
          List.of(party(null, "G1", "123456789", "12345678900012"))),
          AnomalyType.GOLDEN_PARTY_MISMATCH));
    }

    @Test
    @DisplayName("an elemBdrId differing from the golden id IS a mismatch")
    void differingElemBdrIdIsAMismatch() {
      assertTrue(hasType(detector.detect(Flow.INBOUND, KeySpace.SIREN, "k",
          List.of(party("E9", "G1", "123456789", "12345678900012"))),
          AnomalyType.GOLDEN_PARTY_MISMATCH),
          "registration keys on the golden id, so the two differing is worth surfacing");
    }
  }

  // ── Remaining value-type branches ─────────────────────────────────────────

  @Nested
  @DisplayName("value-type null handling")
  class ValueTypes {

    @Test
    @DisplayName("a GuardDecision built with null records exposes an empty list")
    void nullRecordsNormalise() {
      GuardDecision d = new GuardDecision(null, false, false, null);
      assertTrue(d.records().isEmpty());
    }

    @Test
    @DisplayName("a GuardDecision copies the caller's list")
    void recordsAreCopied() {
      List<PartyRegistrationDetails> mutable =
          new ArrayList<>(List.of(party("E1", "G1", "123456789", null)));
      GuardDecision d = new GuardDecision(mutable, false, false, null);
      mutable.clear();
      assertEquals(1, d.records().size());
    }

    @Test
    @DisplayName("an exception with no reason is not retryable — absence is not a maybe")
    void nullReasonIsNotRetryable() {
      assertFalse(new PartyRegistrationUnavailableException(
          null, "SIREN", "k", "no reason given").isRetryable(),
          "a caller must not spin on a failure the domain could not classify");
    }

    @Test
    @DisplayName("the exception keeps a cause when one is supplied")
    void exceptionKeepsCause() {
      Exception cause = new IllegalStateException("socket closed");
      PartyRegistrationUnavailableException e = new PartyRegistrationUnavailableException(
          UnavailabilityReason.UPSTREAM_UNAVAILABLE, "SIREN", "k", "down", "ref-1", cause);
      assertEquals(cause, e.getCause());
      assertEquals("ref-1", e.referenceId());
      assertTrue(e.isRetryable());
    }
  }
}
