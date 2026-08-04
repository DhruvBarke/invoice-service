package com.sg.domaininterface.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.port.out.PartyRegistrationLookup;
import com.sg.domaininterface.port.out.PartyRegistrationUnavailableException;
import com.sg.domaininterface.port.out.UnavailabilityReason;
import com.sg.domaininterface.port.out.AlertNotifier;
import com.sg.domaininterface.port.out.GuardDecision;
import com.sg.domaininterface.port.out.QuarantineRecord;
import com.sg.domaininterface.port.out.QuarantineStatus;
import com.sg.domaininterface.port.out.QuarantineStore;
import com.sg.domaininterface.port.thirdparty.ReferentialUnavailableException;
import com.sg.domaininterface.port.out.ResponseGuard;
import com.sg.domaininterface.model.party.Flow;
import com.sg.domaininterface.model.party.KeySpace;
import com.sg.domaininterface.model.party.RegistrationType;
import com.sg.domaininterface.rule.party.AnomalyType;
import com.sg.domaininterface.rule.party.Servability;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The port contracts: value types, default methods, and the exception surface. */
class PortTypesTest {

  private static final PartyRegistrationDetails ACME = new PartyRegistrationDetails(
      "ELEM-9", "Lyon", "LYON", "TP-1", "Acme SA", "ACME",
      "BDR-G-001", "Acme SA", "ACME", "123456789", "12345678900012", List.of());

  private static final PartyRegistrationDetails OTHER = new PartyRegistrationDetails(
      "ELEM-8", "Paris", "PAR", "TP-2", "Beta SA", "BETA",
      "BDR-G-002", "Beta SA", "BETA", "987654321", "98765432100011", List.of());

  // ── QuarantineRecord ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("QuarantineRecord")
  class Records {

    private QuarantineRecord record(QuarantineStatus status,
                                    List<PartyRegistrationDetails> corrected,
                                    Instant notifiedAt, Long id) {
      return new QuarantineRecord(id, "SIREN", "123456789", "fp",
          Set.of(AnomalyType.MISSING_SIRET), Servability.SERVABLE,
          List.of(ACME), corrected, status,
          Instant.EPOCH, Instant.EPOCH, notifiedAt, null, null);
    }

    @Test
    @DisplayName("a correction only counts once the row is marked CORRECTED and carries content")
    void usableCorrectionNeedsBothStatusAndContent() {
      assertTrue(record(QuarantineStatus.CORRECTED, List.of(ACME), null, 1L)
          .hasUsableCorrection());
      assertFalse(record(QuarantineStatus.PENDING, List.of(ACME), null, 1L)
          .hasUsableCorrection(), "content without the status is a half-finished edit");
      assertFalse(record(QuarantineStatus.CORRECTED, null, null, 1L)
          .hasUsableCorrection(), "the status without content would serve nothing");
      assertFalse(record(QuarantineStatus.CORRECTED, List.of(), null, 1L)
          .hasUsableCorrection(), "an empty list is not a correction");
    }

    @Test
    @DisplayName("notification is gated on the timestamp, which is the durable once-only record")
    void alreadyNotifiedTracksTheTimestamp() {
      assertFalse(record(QuarantineStatus.PENDING, null, null, 1L).alreadyNotified());
      assertTrue(record(QuarantineStatus.PENDING, null, Instant.EPOCH, 1L).alreadyNotified());
    }

    @Test
    @DisplayName("reference is the id an operator quotes, absent before insert")
    void referenceMirrorsTheId() {
      assertEquals("7", record(QuarantineStatus.PENDING, null, null, 7L).reference());
      assertNull(record(QuarantineStatus.PENDING, null, null, null).reference());
    }

    @Test
    @DisplayName("the identity fields are mandatory")
    void identityFieldsMandatory() {
      assertThrows(NullPointerException.class, () -> new QuarantineRecord(
          1L, null, "k", "fp", Set.of(), Servability.SERVABLE, null, null,
          QuarantineStatus.PENDING, null, null, null, null, null));
      assertThrows(NullPointerException.class, () -> new QuarantineRecord(
          1L, "SIREN", null, "fp", Set.of(), Servability.SERVABLE, null, null,
          QuarantineStatus.PENDING, null, null, null, null, null));
      assertThrows(NullPointerException.class, () -> new QuarantineRecord(
          1L, "SIREN", "k", null, Set.of(), Servability.SERVABLE, null, null,
          QuarantineStatus.PENDING, null, null, null, null, null));
      assertThrows(NullPointerException.class, () -> new QuarantineRecord(
          1L, "SIREN", "k", "fp", Set.of(), Servability.SERVABLE, null, null,
          null, null, null, null, null, null));
    }

    @Test
    @DisplayName("collections are defensively copied; null payloads stay null")
    void collectionsAreCopied() {
      List<PartyRegistrationDetails> mutableRaw = new ArrayList<>(List.of(ACME));
      QuarantineRecord r = new QuarantineRecord(1L, "SIREN", "k", "fp",
          null, Servability.SERVABLE, mutableRaw, null,
          QuarantineStatus.PENDING, null, null, null, null, null);

      mutableRaw.clear();
      assertEquals(1, r.rawPayload().size(), "the record keeps its own copy");
      assertTrue(r.anomalyTypes().isEmpty(), "a null anomaly set normalises to empty");
      assertNull(r.correctedPayload(), "…but a null payload stays null, which means 'never set'");
    }

    @Test
    @DisplayName("the same defect hit twice keeps one fingerprint, so it is reported once")
    void fingerprintIsStableForTheSameDefect() {
      String a = QuarantineRecord.fingerprintOf("SIREN", "123456789",
          Set.of(AnomalyType.MISSING_SIRET), List.of(ACME));
      String b = QuarantineRecord.fingerprintOf("SIREN", "123456789",
          Set.of(AnomalyType.MISSING_SIRET), List.of(ACME));
      assertEquals(a, b);
    }

    @Test
    @DisplayName("anomaly-set ordering does not change the fingerprint")
    void fingerprintIsOrderIndependent() {
      Set<AnomalyType> one = new java.util.LinkedHashSet<>(
          List.of(AnomalyType.MISSING_SIRET, AnomalyType.GOLDEN_PARTY_MISMATCH));
      Set<AnomalyType> two = new java.util.LinkedHashSet<>(
          List.of(AnomalyType.GOLDEN_PARTY_MISMATCH, AnomalyType.MISSING_SIRET));
      assertEquals(
          QuarantineRecord.fingerprintOf("SIREN", "k", one, List.of(ACME)),
          QuarantineRecord.fingerprintOf("SIREN", "k", two, List.of(ACME)));
    }

    @Test
    @DisplayName("a changed bad value is a different defect and re-notifies")
    void fingerprintChangesWithContent() {
      assertNotEquals(
          QuarantineRecord.fingerprintOf("SIREN", "k", Set.of(AnomalyType.MISSING_SIRET), List.of(ACME)),
          QuarantineRecord.fingerprintOf("SIREN", "k", Set.of(AnomalyType.MISSING_SIRET), List.of(OTHER)));
    }

    @Test
    @DisplayName("different anomaly sets are different defects")
    void fingerprintChangesWithAnomalies() {
      assertNotEquals(
          QuarantineRecord.fingerprintOf("SIREN", "k", Set.of(AnomalyType.MISSING_SIRET), List.of(ACME)),
          QuarantineRecord.fingerprintOf("SIREN", "k", Set.of(AnomalyType.MISSING_SIREN), List.of(ACME)));
    }

    @Test
    @DisplayName("null and empty payloads hash alike — both mean 'nothing came back'")
    void nullAndEmptyPayloadHashAlike() {
      assertEquals(
          QuarantineRecord.fingerprintOf("SIREN", "k", Set.of(AnomalyType.NO_REGISTRATION_FOUND), null),
          QuarantineRecord.fingerprintOf("SIREN", "k", Set.of(AnomalyType.NO_REGISTRATION_FOUND), List.of()));
    }

    @Test
    @DisplayName("the fingerprint fits the column, however long the key")
    void fingerprintIsTruncatedToColumnWidth() {
      String fp = QuarantineRecord.fingerprintOf("SIREN", "x".repeat(500),
          Set.of(AnomalyType.MISSING_SIRET), List.of(ACME));
      assertEquals(128, fp.length(), "anomaly_fingerprint is VARCHAR(128)");
    }
  }

  // ── GuardDecision ─────────────────────────────────────────────────────────

  @Nested
  @DisplayName("GuardDecision")
  class Decisions {

    @Test
    @DisplayName("pass serves the records with nothing to quote")
    void passServesRecords() {
      GuardDecision d = GuardDecision.pass(List.of(ACME));
      assertEquals(List.of(ACME), d.records());
      assertFalse(d.blocked());
      assertNull(d.referenceId());
      assertFalse(d.volatileTtl());
    }

    @Test
    @DisplayName("serveVolatile serves records but marks the entry short-lived")
    void serveVolatileMarksTheEntry() {
      GuardDecision d = GuardDecision.serveVolatile(List.of(ACME), "42");
      assertEquals(List.of(ACME), d.records());
      assertFalse(d.blocked());
      assertEquals("42", d.referenceId());
      assertTrue(d.volatileTtl(),
          "a served-but-defective record must expire quickly so a correction reaches callers");
    }

    @Test
    @DisplayName("block withholds the records and carries the reference an operator quotes")
    void blockWithholdsRecords() {
      GuardDecision d = GuardDecision.block("42");
      assertTrue(d.blocked());
      assertEquals("42", d.referenceId());
      assertTrue(d.records() == null || d.records().isEmpty());
    }
  }

  // ── Exceptions and enums ──────────────────────────────────────────────────

  @Nested
  @DisplayName("PartyRegistrationUnavailableException")
  class Exceptions {

    @Test
    @DisplayName("carries the reason, the key that failed, and the retry hint")
    void carriesDiagnostics() {
      PartyRegistrationUnavailableException e = new PartyRegistrationUnavailableException(
          UnavailabilityReason.NOT_FOUND, "SIREN", "123456789", "nothing found");

      assertEquals(UnavailabilityReason.NOT_FOUND, e.reason());
      assertEquals("SIREN", e.keySpace());
      assertEquals("123456789", e.lookupKey());
      assertNull(e.referenceId());
      assertFalse(e.isRetryable(), "an absent party will still be absent on retry");
      assertTrue(e.getMessage().contains("nothing found"));
    }

    @Test
    @DisplayName("the quarantine reference is surfaced when one exists")
    void surfacesQuarantineReference() {
      PartyRegistrationUnavailableException e = new PartyRegistrationUnavailableException(
          UnavailabilityReason.BLOCKED, "SIREN", "123456789", "blocked", "4471", null);
      assertEquals("4471", e.referenceId(),
          "'invoice rejected, fix row 4471' beats a bare failure");
    }

    @Test
    @DisplayName("an upstream outage is retryable where a missing party is not")
    void retryabilityFollowsTheReason() {
      assertTrue(new PartyRegistrationUnavailableException(
          UnavailabilityReason.UPSTREAM_UNAVAILABLE, "SIREN", "k", "down").isRetryable());
    }

    @ParameterizedTest
    @EnumSource(UnavailabilityReason.class)
    @DisplayName("every reason declares its retryability")
    void everyReasonDeclaresRetryability(UnavailabilityReason reason) {
      assertEquals(reason.isRetryable(), new PartyRegistrationUnavailableException(
          reason, "SIREN", "k", "m").isRetryable());
    }

    @Test
    @DisplayName("a referential failure names the referential and says whether to retry")
    void referentialUnavailable() {
      Exception cause = new IllegalStateException("socket closed");

      ReferentialUnavailableException transientFailure =
          new ReferentialUnavailableException("party-registration", "upstream down", true, cause);
      assertSame(cause, transientFailure.getCause());
      assertEquals("upstream down", transientFailure.getMessage());
      assertEquals("party-registration", transientFailure.referential(),
          "an alert that cannot name which referential failed sends someone looking at three");
      assertTrue(transientFailure.isRetryable());

      // A 4xx will be just as wrong next time. Retrying it only adds load to something that is
      // already telling us the request is the problem.
      assertFalse(new ReferentialUnavailableException("sgdoc", "bad request", false, null)
          .isRetryable());
    }
  }

  @Nested
  @DisplayName("enums")
  class Enums {

    @ParameterizedTest
    @EnumSource(AnomalyType.class)
    @DisplayName("every anomaly declares a servability, and isBlocking agrees with it")
    void anomalyServability(AnomalyType type) {
      assertNotNull(type.servability());
      assertEquals(type.servability() == Servability.BLOCKING, type.isBlocking());
    }

    @ParameterizedTest
    @EnumSource(QuarantineStatus.class)
    @DisplayName("quarantine statuses round-trip by name")
    void quarantineStatusRoundTrip(QuarantineStatus s) {
      assertEquals(s, QuarantineStatus.valueOf(s.name()));
    }

    @ParameterizedTest
    @EnumSource(Servability.class)
    @DisplayName("servability values round-trip by name")
    void servabilityRoundTrip(Servability s) {
      assertEquals(s, Servability.valueOf(s.name()));
    }

    @ParameterizedTest
    @EnumSource(Flow.class)
    @DisplayName("flow values round-trip by name")
    void flowRoundTrip(Flow f) {
      assertEquals(f, Flow.valueOf(f.name()));
    }

    @ParameterizedTest
    @EnumSource(RegistrationType.class)
    @DisplayName("registration types round-trip by name")
    void registrationTypeRoundTrip(RegistrationType t) {
      assertEquals(t, RegistrationType.valueOf(t.name()));
    }
  }

  // ── Default methods on the ports ──────────────────────────────────────────

  @Nested
  @DisplayName("PartyRegistrationLookup defaults")
  class LookupDefaults {

    private PartyRegistrationLookup lookup(PartyRegistrationDetails result) {
      return new PartyRegistrationLookup() {
        @Override public Optional<PartyRegistrationDetails> findByBdrId(String b) {
          return Optional.ofNullable(result);
        }
        @Override public Optional<PartyRegistrationDetails> findBySiren(String s) {
          return Optional.ofNullable(result);
        }
        @Override public Optional<PartyRegistrationDetails> findBySiret(String s) {
          return Optional.ofNullable(result);
        }
        @Override public List<PartyRegistrationDetails> findAllBySiret(String s) {
          return result == null ? List.of() : List.of(result);
        }
      };
    }

    @Test
    @DisplayName("find dispatches on the registration type")
    void findDispatchesByType() {
      PartyRegistrationLookup l = lookup(ACME);
      assertEquals(ACME, l.find(RegistrationType.SIREN, "123456789").orElseThrow());
      assertEquals(ACME, l.find(RegistrationType.SIRET, "12345678900012").orElseThrow());
    }

    @Test
    @DisplayName("requireByBdrId returns the record when present")
    void requireByBdrIdPresent() {
      assertEquals(ACME, lookup(ACME).requireByBdrId("BDR-G-001"));
    }

    @Test
    @DisplayName("requireByBdrId raises NOT_FOUND when absent")
    void requireByBdrIdAbsent() {
      PartyRegistrationUnavailableException e = assertThrows(
          PartyRegistrationUnavailableException.class,
          () -> lookup(null).requireByBdrId("BDR-404"));
      assertEquals(UnavailabilityReason.NOT_FOUND, e.reason());
      assertEquals("BDR_ID", e.keySpace());
      assertFalse(e.isRetryable());
    }

    @Test
    @DisplayName("requireBySiren returns the record when present")
    void requireBySirenPresent() {
      assertEquals(ACME, lookup(ACME).requireBySiren("123456789"));
    }

    @Test
    @DisplayName("requireBySiren raises NOT_FOUND when absent")
    void requireBySirenAbsent() {
      PartyRegistrationUnavailableException e = assertThrows(
          PartyRegistrationUnavailableException.class,
          () -> lookup(null).requireBySiren("000000000"));
      assertEquals(UnavailabilityReason.NOT_FOUND, e.reason());
      assertEquals("SIREN", e.keySpace());
    }
  }

  @Nested
  @DisplayName("other port defaults")
  class OtherDefaults {

    @Test
    @DisplayName("the no-op notifier accepts a notification and does nothing")
    void noOpNotifier() {
      AlertNotifier.none().notify(new AlertNotifier.Notification(
          AnomalyType.MISSING_SIRET, Servability.SERVABLE, Flow.INBOUND, "fp",
          "message", Instant.EPOCH, Map.of("k", "v"), List.of(ACME)));
    }

    @Test
    @DisplayName("a notification defensively copies its context and samples")
    void notificationCopiesCollections() {
      Map<String, String> ctx = new java.util.HashMap<>(Map.of("k", "v"));
      List<PartyRegistrationDetails> samples = new ArrayList<>(List.of(ACME));

      AlertNotifier.Notification n = new AlertNotifier.Notification(
          AnomalyType.MISSING_SIRET, Servability.SERVABLE, Flow.INBOUND, "fp",
          "m", Instant.EPOCH, ctx, samples);

      ctx.clear();
      samples.clear();
      assertEquals(1, n.context().size());
      assertEquals(1, n.samples().size());
    }

    @Test
    @DisplayName("null context and samples normalise to empty")
    void notificationNullsNormalise() {
      AlertNotifier.Notification n = new AlertNotifier.Notification(
          AnomalyType.MISSING_SIRET, Servability.SERVABLE, Flow.INBOUND, "fp",
          "m", Instant.EPOCH, null, null);
      assertTrue(n.context().isEmpty());
      assertTrue(n.samples().isEmpty());
    }

    @Test
    @DisplayName("an upsert result reports whether the caller should notify")
    void upsertResult() {
      QuarantineRecord r = new QuarantineRecord(1L, "SIREN", "k", "fp",
          Set.of(), Servability.SERVABLE, null, null, QuarantineStatus.PENDING,
          null, null, null, null, null);
      QuarantineStore.UpsertResult yes = new QuarantineStore.UpsertResult(r, true);
      assertSame(r, yes.record());
      assertTrue(yes.needsNotification());
      assertFalse(new QuarantineStore.UpsertResult(r, false).needsNotification());
    }

    @Test
    @DisplayName("the pass-through guard serves everything untouched")
    void passThroughGuard() {
      GuardDecision d = ResponseGuard.passThrough().inspect(
          Flow.INBOUND, KeySpace.SIREN, "123456789", List.of(ACME));
      assertFalse(d.blocked(), "the safety switch disables detection, quarantine AND blocking");
      assertEquals(List.of(ACME), d.records());
    }
  }
}
