package com.sg.domain.quarantine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domaininterface.model.party.Flow;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.port.out.AlertNotifier;
import com.sg.domaininterface.port.out.QuarantineRecord;
import com.sg.domaininterface.port.out.QuarantineStatus;
import com.sg.domaininterface.port.out.QuarantineStore;
import com.sg.domaininterface.rule.party.Anomaly;
import com.sg.domaininterface.rule.party.AnomalyType;
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
 * The quarantine workflow: record once, notify once, serve a correction in preference to the
 * referential, and never let bookkeeping failures take down a lookup.
 */
class QuarantineServiceTest {

  private static PartyRegistrationDetails party(String siren) {
    return new PartyRegistrationDetails("E1", "elem", "EMN", "TP1", "tp", "TPM",
        "G1", "Acme SA", "ACME", siren, "12345678900012", List.of());
  }

  private static Anomaly anomaly(AnomalyType type, PartyRegistrationDetails subject) {
    return new Anomaly(type, type.name() + " detail", subject);
  }

  /** A store that records every call, with configurable responses. */
  private static class FakeStore implements QuarantineStore {
    QuarantineRecord active;
    boolean needsNotification = true;
    RuntimeException failOnFindActive;
    RuntimeException failOnMarkNotified;
    final List<Long> notified = new ArrayList<>();
    final List<Long> softDeleted = new ArrayList<>();
    QuarantineRecord lastUpserted;

    @Override public Optional<QuarantineRecord> findActive(String keySpace, String lookupKey) {
      if (failOnFindActive != null) throw failOnFindActive;
      return Optional.ofNullable(active);
    }
    @Override public UpsertResult upsert(QuarantineRecord record) {
      lastUpserted = withId(record, 7L);
      return new UpsertResult(lastUpserted, needsNotification);
    }
    @Override public void markNotified(long id, Instant at) {
      if (failOnMarkNotified != null) throw failOnMarkNotified;
      notified.add(id);
    }
    @Override public QuarantineRecord applyCorrection(long id,
        List<PartyRegistrationDetails> corrected, String by, String notes) {
      return active;
    }
    @Override public void softDelete(long id, String deletedBy) { softDeleted.add(id); }
    @Override public List<QuarantineRecord> findChangedSince(Instant since, int limit) {
      return List.of();
    }
    @Override public List<QuarantineRecord> findByStatus(QuarantineStatus s, int limit) {
      return List.of();
    }
  }

  private static QuarantineRecord withId(QuarantineRecord r, Long id) {
    return new QuarantineRecord(id, r.keySpace(), r.lookupKey(), r.fingerprint(),
        r.anomalyTypes(), r.servability(), r.rawPayload(), r.correctedPayload(),
        r.status(), r.detectedAt(), r.updatedAt(), r.notifiedAt(), r.correctedBy(), r.notes());
  }

  private static QuarantineRecord corrected(List<PartyRegistrationDetails> payload) {
    return new QuarantineRecord(7L, "SIREN", "123456789", "fp",
        Set.of(AnomalyType.MISSING_SIRET), Servability.SERVABLE,
        List.of(party("123456789")), payload, QuarantineStatus.CORRECTED,
        Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, "ops", null);
  }

  private static final class CapturingNotifier implements AlertNotifier {
    final List<Notification> sent = new ArrayList<>();
    @Override public void notify(Notification n) { sent.add(n); }
  }

  // ── Recording and notifying ───────────────────────────────────────────────

  @Nested
  @DisplayName("recording a defect")
  class Recording {

    @Test
    @DisplayName("a servable defect is recorded, reported once, and still served")
    void servableDefectIsServed() {
      FakeStore store = new FakeStore();
      CapturingNotifier notifier = new CapturingNotifier();
      QuarantineService service = new QuarantineService(store, notifier);

      List<PartyRegistrationDetails> response = List.of(party("123456789"));
      QuarantineService.Verdict verdict = service.handle(Flow.INBOUND, "SIREN", "123456789",
          response, List.of(anomaly(AnomalyType.MISSING_SIRET, party("123456789"))));

      assertFalse(verdict.blocked());
      assertEquals(response, verdict.records(), "a cosmetic gap must not stall invoicing");
      assertEquals("7", verdict.referenceId());
      assertEquals(1, notifier.sent.size());
      assertEquals(List.of(7L), store.notified,
          "the notify-once gate lives in the database, not in memory");
    }

    @Test
    @DisplayName("a blocking defect withholds the records and carries the row to quote")
    void blockingDefectWithholds() {
      FakeStore store = new FakeStore();
      CapturingNotifier notifier = new CapturingNotifier();
      QuarantineService service = new QuarantineService(store, notifier);

      QuarantineService.Verdict verdict = service.handle(Flow.INBOUND, "SIREN", "123456789",
          List.of(party(null)), List.of(anomaly(AnomalyType.MISSING_SIREN, party(null))));

      assertTrue(verdict.blocked());
      assertTrue(verdict.records().isEmpty());
      assertEquals("7", verdict.referenceId());
      assertEquals(1, service.stats().blocked());
    }

    @Test
    @DisplayName("a repeat of the same defect is silent")
    void repeatIsSilent() {
      FakeStore store = new FakeStore();
      store.needsNotification = false;
      CapturingNotifier notifier = new CapturingNotifier();

      new QuarantineService(store, notifier).handle(Flow.INBOUND, "SIREN", "123456789",
          List.of(party("123456789")), List.of(anomaly(AnomalyType.MISSING_SIRET, null)));

      assertTrue(notifier.sent.isEmpty(),
          "reporting the same defect on every lookup would bury the new ones");
      assertTrue(store.notified.isEmpty());
    }

    @Test
    @DisplayName("the notification carries the context an operator needs to act")
    void notificationCarriesContext() {
      FakeStore store = new FakeStore();
      CapturingNotifier notifier = new CapturingNotifier();

      new QuarantineService(store, notifier).handle(Flow.INBOUND, "SIREN", "123456789",
          List.of(party("123456789")),
          List.of(anomaly(AnomalyType.MISSING_SIRET, party("123456789")),
                  anomaly(AnomalyType.GOLDEN_PARTY_MISMATCH, party("123456789"))));

      AlertNotifier.Notification n = notifier.sent.get(0);
      assertEquals("INBOUND", n.context().get("flow"));
      assertEquals("SIREN", n.context().get("keySpace"));
      assertEquals("123456789", n.context().get("lookupKey"));
      assertEquals("7", n.context().get("quarantineRowId"));
      assertTrue(n.context().get("anomalies").contains("MISSING_SIRET"));
      assertTrue(n.context().get("anomalies").contains("GOLDEN_PARTY_MISMATCH"));
      assertTrue(n.context().get("action").contains("served"));
      assertTrue(n.message().contains(";"), "both anomaly details are joined into one message");
      assertEquals(2, n.samples().size());
    }

    @Test
    @DisplayName("a blocking notification says the key is blocked")
    void blockingNotificationSaysSo() {
      CapturingNotifier notifier = new CapturingNotifier();
      new QuarantineService(new FakeStore(), notifier).handle(Flow.OUTBOUND, "BDR_ID", "G1",
          List.of(), List.of(anomaly(AnomalyType.NO_REGISTRATION_FOUND, null)));

      assertTrue(notifier.sent.get(0).context().get("action").contains("BLOCKED"));
      assertTrue(notifier.sent.get(0).samples().isEmpty(),
          "an anomaly with no subject contributes no sample");
    }

    @Test
    @DisplayName("samples are capped so a notification never carries an unbounded payload")
    void samplesAreCapped() {
      CapturingNotifier notifier = new CapturingNotifier();
      List<Anomaly> many = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        many.add(anomaly(AnomalyType.MISSING_SIRET, party("12345678" + i)));
      }

      new QuarantineService(new FakeStore(), notifier)
          .handle(Flow.INBOUND, "SIREN", "123456789", List.of(party("123456789")), many);

      assertEquals(3, notifier.sent.get(0).samples().size());
    }
  }

  // ── Corrections ───────────────────────────────────────────────────────────

  @Nested
  @DisplayName("corrections")
  class Corrections {

    @Test
    @DisplayName("a usable correction outranks the referential and is served silently")
    void correctionOutranksTheReferential() {
      FakeStore store = new FakeStore();
      store.active = corrected(List.of(party("987654321")));
      CapturingNotifier notifier = new CapturingNotifier();
      QuarantineService service = new QuarantineService(store, notifier);

      QuarantineService.Verdict verdict = service.handle(Flow.INBOUND, "SIREN", "123456789",
          List.of(party("123456789")), List.of(anomaly(AnomalyType.MISSING_SIRET, null)));

      assertTrue(verdict.corrected());
      assertFalse(verdict.blocked());
      assertEquals("987654321", verdict.records().get(0).siren());
      assertTrue(notifier.sent.isEmpty(),
          "the defect is already known and already handled — reporting it again is noise");
      assertEquals(1, service.stats().correctionsServed());
    }

    @Test
    @DisplayName("a correction with no usable SIREN is rejected and the row stays blocked")
    void unusableCorrectionIsRejected() {
      FakeStore store = new FakeStore();
      store.active = corrected(List.of(party(null)));
      CapturingNotifier notifier = new CapturingNotifier();

      QuarantineService.Verdict verdict = new QuarantineService(store, notifier)
          .handle(Flow.INBOUND, "SIREN", "123456789", List.of(party(null)),
              List.of(anomaly(AnomalyType.MISSING_SIREN, null)));

      assertFalse(verdict.corrected(),
          "an operator correction that is itself defective must not be served");
      assertTrue(verdict.blocked());
    }
  }

  // ── Availability over bookkeeping ─────────────────────────────────────────

  @Nested
  @DisplayName("when the store is unavailable")
  class StoreFailures {

    @Test
    @DisplayName("a servable defect is still served, unrecorded, and still reported")
    void servableSurvivesAStoreOutage() {
      FakeStore store = new FakeStore();
      store.failOnFindActive = new IllegalStateException("database down");
      CapturingNotifier notifier = new CapturingNotifier();
      QuarantineService service = new QuarantineService(store, notifier);

      List<PartyRegistrationDetails> response = List.of(party("123456789"));
      QuarantineService.Verdict verdict = service.handle(Flow.INBOUND, "SIREN", "123456789",
          response, List.of(anomaly(AnomalyType.MISSING_SIRET, null)));

      assertEquals(response, verdict.records(), "availability outranks bookkeeping");
      assertFalse(verdict.blocked());
      assertEquals(1, notifier.sent.size(), "the defect must not be lost entirely");
      assertEquals("NOT_PERSISTED", notifier.sent.get(0).context().get("quarantineRowId"));
      assertEquals(1, service.stats().storeFailures());
    }

    @Test
    @DisplayName("a blocking defect still blocks even when it cannot be recorded")
    void blockingStillBlocks() {
      FakeStore store = new FakeStore();
      store.failOnFindActive = new IllegalStateException("database down");

      QuarantineService.Verdict verdict = new QuarantineService(store, new CapturingNotifier())
          .handle(Flow.INBOUND, "SIREN", "123456789", List.of(party(null)),
              List.of(anomaly(AnomalyType.MISSING_SIREN, null)));

      assertTrue(verdict.blocked(),
          "an unusable record must not reach registration because the audit trail is down");
      assertTrue(verdict.records().isEmpty());
    }

    @Test
    @DisplayName("failing to mark a row notified is tolerated — worst case is a repeat")
    void markNotifiedFailureIsTolerated() {
      FakeStore store = new FakeStore();
      store.failOnMarkNotified = new IllegalStateException("write conflict");

      QuarantineService.Verdict verdict = new QuarantineService(store, new CapturingNotifier())
          .handle(Flow.INBOUND, "SIREN", "123456789", List.of(party("123456789")),
              List.of(anomaly(AnomalyType.MISSING_SIRET, null)));

      assertFalse(verdict.blocked(), "reporting twice beats failing the lookup");
    }

    @Test
    @DisplayName("a notifier that throws never reaches the caller")
    void notifierFailureIsSwallowed() {
      AlertNotifier exploding = n -> { throw new IllegalStateException("SMTP down"); };

      QuarantineService.Verdict verdict = new QuarantineService(new FakeStore(), exploding)
          .handle(Flow.INBOUND, "SIREN", "123456789", List.of(party("123456789")),
              List.of(anomaly(AnomalyType.MISSING_SIRET, null)));

      assertFalse(verdict.blocked());
    }
  }

  // ── Auto-retirement ───────────────────────────────────────────────────────

  @Nested
  @DisplayName("auto-retirement")
  class Retirement {

    @Test
    @DisplayName("a row whose defect disappeared upstream is retired")
    void resolvedRowIsRetired() {
      FakeStore store = new FakeStore();
      store.active = corrected(List.of(party("987654321")));

      new QuarantineService(store, new CapturingNotifier())
          .retireIfResolved("SIREN", "123456789");

      assertEquals(List.of(7L), store.softDeleted,
          "a stale override must stop shadowing since-fixed upstream data");
    }

    @Test
    @DisplayName("nothing to retire is not an error")
    void noRowIsFine() {
      FakeStore store = new FakeStore();
      new QuarantineService(store, new CapturingNotifier())
          .retireIfResolved("SIREN", "123456789");
      assertTrue(store.softDeleted.isEmpty());
    }

    @Test
    @DisplayName("a store failure during retirement is swallowed")
    void retirementFailureIsSwallowed() {
      FakeStore store = new FakeStore();
      store.failOnFindActive = new IllegalStateException("database down");

      new QuarantineService(store, new CapturingNotifier())
          .retireIfResolved("SIREN", "123456789");
      // Reaching here without an exception is the assertion.
    }
  }

  // ── Contracts ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("both collaborators are mandatory")
  void collaboratorsMandatory() {
    assertThrows(NullPointerException.class,
        () -> new QuarantineService(null, new CapturingNotifier()));
    assertThrows(NullPointerException.class,
        () -> new QuarantineService(new FakeStore(), null));
  }

  @Test
  @DisplayName("stats accumulate across calls")
  void statsAccumulate() {
    FakeStore store = new FakeStore();
    QuarantineService service = new QuarantineService(store, new CapturingNotifier());

    service.handle(Flow.INBOUND, "SIREN", "1", List.of(party("123456789")),
        List.of(anomaly(AnomalyType.MISSING_SIRET, null)));
    service.handle(Flow.INBOUND, "SIREN", "2", List.of(party("123456789")),
        List.of(anomaly(AnomalyType.MISSING_SIRET, null)));

    assertEquals(2, service.stats().detected());
    assertEquals(2, service.stats().notifications());
  }
}
