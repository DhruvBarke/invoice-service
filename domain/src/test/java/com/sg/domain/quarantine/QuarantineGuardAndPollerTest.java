package com.sg.domain.quarantine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domain.quarantine.QuarantineService;
import com.sg.domaininterface.model.party.Flow;
import com.sg.domaininterface.model.party.KeySpace;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.port.out.AlertNotifier;
import com.sg.domaininterface.port.out.GuardDecision;
import com.sg.domaininterface.port.out.QuarantineRecord;
import com.sg.domaininterface.port.out.QuarantineStatus;
import com.sg.domaininterface.port.out.QuarantineStore;
import com.sg.domain.party.AnomalyDetector;
import com.sg.domaininterface.rule.party.AnomalyType;
import com.sg.domaininterface.rule.party.DetectionPolicy;
import com.sg.domaininterface.rule.party.Servability;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two pieces that connect quarantine to the rest of the system: the guard that turns
 * detection into a cache decision, and the poller that propagates a correction across pods.
 */
class QuarantineGuardAndPollerTest {

  private static PartyRegistrationDetails party(String siren, String siret) {
    return new PartyRegistrationDetails(null, null, null, null, null, null,
        "G1", "Acme SA", "ACME", siren, siret, List.of());
  }

  /** Minimal store: no rows, upserts succeed, everything else is a no-op. */
  private static class EmptyStore implements QuarantineStore {
    final List<Long> softDeleted = new ArrayList<>();
    QuarantineRecord active;

    @Override public Optional<QuarantineRecord> findActive(String ks, String key) {
      return Optional.ofNullable(active);
    }
    @Override public UpsertResult upsert(QuarantineRecord r) {
      return new UpsertResult(new QuarantineRecord(1L, r.keySpace(), r.lookupKey(),
          r.fingerprint(), r.anomalyTypes(), r.servability(), r.rawPayload(),
          r.correctedPayload(), r.status(), r.detectedAt(), r.updatedAt(), r.notifiedAt(),
          r.correctedBy(), r.notes()), true);
    }
    @Override public void markNotified(long id, Instant at) { }
    @Override public QuarantineRecord applyCorrection(long id,
        List<PartyRegistrationDetails> c, String by, String notes) { return active; }
    @Override public void softDelete(long id, String by) { softDeleted.add(id); }
    @Override public List<QuarantineRecord> findChangedSince(Instant since, int limit) {
      return List.of();
    }
    @Override public List<QuarantineRecord> findByStatus(QuarantineStatus s, int limit) {
      return List.of();
    }
  }

  private static QuarantineService service(QuarantineStore store) {
    return new QuarantineService(store, AlertNotifier.none());
  }

  private static QuarantiningResponseGuard guard(QuarantineStore store, boolean autoRetire) {
    return new QuarantiningResponseGuard(
        new AnomalyDetector(DetectionPolicy.defaults()), service(store), autoRetire);
  }

  // ── The guard ─────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("QuarantiningResponseGuard")
  class Guard {

    @Test
    @DisplayName("a clean response passes through at the normal lifetime")
    void cleanResponsePasses() {
      List<PartyRegistrationDetails> clean = List.of(party("123456789", "12345678900012"));

      GuardDecision decision = guard(new EmptyStore(), false)
          .inspect(Flow.INBOUND, KeySpace.SIREN, "123456789", clean);

      assertFalse(decision.blocked());
      assertFalse(decision.volatileTtl(), "a healthy entry earns the full lifetime");
      assertEquals(clean, decision.records());
    }

    @Test
    @DisplayName("a servable defect is served but held only briefly")
    void servableDefectIsVolatile() {
      // No SIRET: servable, but worth reporting and worth re-checking soon.
      List<PartyRegistrationDetails> response = List.of(party("123456789", null));

      GuardDecision decision = guard(new EmptyStore(), false)
          .inspect(Flow.INBOUND, KeySpace.SIREN, "123456789", response);

      assertFalse(decision.blocked());
      assertTrue(decision.volatileTtl(),
          "an upstream fix should be picked up without waiting a full cache lifetime");
      assertEquals("1", decision.referenceId());
    }

    @Test
    @DisplayName("a blocking defect withholds the records and carries the row to quote")
    void blockingDefectBlocks() {
      List<PartyRegistrationDetails> response = List.of(party(null, "12345678900012"));

      GuardDecision decision = guard(new EmptyStore(), false)
          .inspect(Flow.INBOUND, KeySpace.SIREN, "123456789", response);

      assertTrue(decision.blocked());
      assertTrue(decision.records().isEmpty());
      assertEquals("1", decision.referenceId());
    }

    @Test
    @DisplayName("nothing found at all is blocking")
    void nothingFoundBlocks() {
      assertTrue(guard(new EmptyStore(), false)
          .inspect(Flow.INBOUND, KeySpace.SIREN, "123456789", List.of()).blocked());
    }

    @Test
    @DisplayName("with auto-retire on, a now-clean response retires the stale row")
    void autoRetireOnCleanResponse() {
      EmptyStore store = new EmptyStore();
      store.active = new QuarantineRecord(9L, "SIREN", "123456789", "fp",
          Set.of(AnomalyType.MISSING_SIRET), Servability.SERVABLE, null, null,
          QuarantineStatus.PENDING, Instant.EPOCH, Instant.EPOCH, null, null, null);

      guard(store, true).inspect(Flow.INBOUND, KeySpace.SIREN, "123456789",
          List.of(party("123456789", "12345678900012")));

      assertEquals(List.of(9L), store.softDeleted,
          "a correction written months ago must stop shadowing since-fixed data");
    }

    @Test
    @DisplayName("with auto-retire off, a clean response leaves the row alone")
    void noAutoRetireWhenDisabled() {
      EmptyStore store = new EmptyStore();
      store.active = new QuarantineRecord(9L, "SIREN", "123456789", "fp",
          Set.of(AnomalyType.MISSING_SIRET), Servability.SERVABLE, null, null,
          QuarantineStatus.PENDING, Instant.EPOCH, Instant.EPOCH, null, null, null);

      guard(store, false).inspect(Flow.INBOUND, KeySpace.SIREN, "123456789",
          List.of(party("123456789", "12345678900012")));

      assertTrue(store.softDeleted.isEmpty(),
          "retiring a row without a human is a deliberate opt-in");
    }

    @Test
    @DisplayName("all three collaborators are mandatory")
    void collaboratorsMandatory() {
      AnomalyDetector detector = new AnomalyDetector(DetectionPolicy.defaults());
      QuarantineService svc = service(new EmptyStore());

      assertThrows(NullPointerException.class,
          () -> new QuarantiningResponseGuard(null, svc, false));
      assertThrows(NullPointerException.class,
          () -> new QuarantiningResponseGuard(detector, null, false));
    }
  }
}
