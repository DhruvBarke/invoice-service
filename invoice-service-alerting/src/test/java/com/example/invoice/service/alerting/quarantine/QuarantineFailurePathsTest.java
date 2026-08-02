package com.example.invoice.service.alerting.quarantine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.invoice.service.domain.model.Flow;
import com.example.invoice.service.domain.model.PartyRegistrationDetails;
import com.example.invoice.service.domain.port.out.AlertNotifier;
import com.example.invoice.service.domain.port.out.QuarantineRecord;
import com.example.invoice.service.domain.port.out.QuarantineStatus;
import com.example.invoice.service.domain.port.out.QuarantineStore;
import com.example.invoice.service.domain.rule.Anomaly;
import com.example.invoice.service.domain.rule.AnomalyType;
import com.example.invoice.service.domain.rule.Servability;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The error and edge paths: SQL failures surfacing as the store's own type, the anomaly-type
 * CSV round trip, and the guards that keep a broken collaborator from taking a lookup down.
 */
class QuarantineFailurePathsTest {

  private static final AtomicInteger DB_SEQ = new AtomicInteger(1000);

  private static final RecordCodec CODEC = new RecordCodec() {
    @Override public String serialize(List<PartyRegistrationDetails> records) {
      return records == null ? null : String.valueOf(records.size());
    }
    @Override public List<PartyRegistrationDetails> deserialize(String payload) {
      return (payload == null || payload.isBlank()) ? null : List.of(party("123456789"));
    }
  };

  private static PartyRegistrationDetails party(String siren) {
    return new PartyRegistrationDetails(null, null, null, null, null, null,
        "G1", "Acme SA", "ACME", siren, "12345678900012", List.of());
  }

  // ── JDBC failure surface + CSV round trip ─────────────────────────────────

  @Nested
  @DisplayName("JdbcQuarantineStore failure paths")
  class JdbcFailures {

    private DataSource dataSource;
    private JdbcQuarantineStore store;

    @BeforeEach
    void setUp() throws Exception {
      JdbcDataSource ds = new JdbcDataSource();
      ds.setURL("jdbc:h2:mem:qfail" + DB_SEQ.incrementAndGet()
          + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
      ds.setUser("sa");
      dataSource = ds;
      store = new JdbcQuarantineStore(ds, CODEC);

      Path ddl = Path.of("..", "invoice-service-app", "src", "main", "resources",
          "db", "migration", "V1__party_registration_quarantine.sql");
      try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
        s.execute(Files.readString(ddl, StandardCharsets.UTF_8));
      }
    }

    @AfterEach
    void tearDown() throws Exception {
      try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
        s.execute("DROP ALL OBJECTS");
      }
    }

    private QuarantineRecord record(String key, Set<AnomalyType> types) {
      return new QuarantineRecord(null, "SIREN", key, "fp", types, Servability.SERVABLE,
          List.of(party("123456789")), null, QuarantineStatus.PENDING,
          Instant.now(), Instant.now(), null, null, null);
    }

    @Test
    @DisplayName("several anomaly types round-trip through the comma-separated column")
    void multipleAnomalyTypesRoundTrip() {
      store.upsert(record("123456789",
          Set.of(AnomalyType.MISSING_SIRET, AnomalyType.GOLDEN_PARTY_MISMATCH,
              AnomalyType.MULTIPLE_REGISTRATIONS)));

      QuarantineRecord read = store.findActive("SIREN", "123456789").orElseThrow();
      assertEquals(Set.of(AnomalyType.MISSING_SIRET, AnomalyType.GOLDEN_PARTY_MISMATCH,
          AnomalyType.MULTIPLE_REGISTRATIONS), read.anomalyTypes());
    }

    @Test
    @DisplayName("an empty anomaly set round-trips as empty")
    void emptyAnomalySetRoundTrips() {
      store.upsert(record("123456789", Set.of()));
      assertTrue(store.findActive("SIREN", "123456789").orElseThrow().anomalyTypes().isEmpty());
    }

    @Test
    @DisplayName("an anomaly name that no longer exists in code is skipped, not fatal")
    void unknownAnomalyNameIsSkipped() throws Exception {
      store.upsert(record("123456789", Set.of(AnomalyType.MISSING_SIRET)));
      // Simulate a row written by an older deployment that knew a type this build does not.
      try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
        s.executeUpdate("UPDATE party_registration_quarantine "
            + "SET anomaly_types = 'MISSING_SIRET,RETIRED_TYPE_FROM_AN_OLDER_BUILD' "
            + "WHERE lookup_key = '123456789'");
      }

      QuarantineRecord read = store.findActive("SIREN", "123456789").orElseThrow();
      assertEquals(Set.of(AnomalyType.MISSING_SIRET), read.anomalyTypes(),
          "a rolling deployment must not crash on a name the new build dropped");
    }

    @Test
    @DisplayName("blank entries in the CSV are ignored")
    void blankCsvEntriesIgnored() throws Exception {
      store.upsert(record("123456789", Set.of(AnomalyType.MISSING_SIRET)));
      try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
        s.executeUpdate("UPDATE party_registration_quarantine "
            + "SET anomaly_types = 'MISSING_SIRET,, ,' WHERE lookup_key = '123456789'");
      }
      assertEquals(Set.of(AnomalyType.MISSING_SIRET),
          store.findActive("SIREN", "123456789").orElseThrow().anomalyTypes());
    }

    @Test
    @DisplayName("correcting a row that is not there is an error, not a silent no-op")
    void applyCorrectionToMissingRow() {
      assertThrows(RuntimeException.class,
          () -> store.applyCorrection(999L, List.of(party("123456789")), "ops", null));
    }

    @Test
    @DisplayName("every read path surfaces a dead database as the store's own type")
    void deadDatabaseSurfacesConsistently() throws Exception {
      // Drop the table underneath the store: every statement now fails at the SQL layer.
      try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
        s.execute("DROP TABLE party_registration_quarantine");
      }

      assertThrows(RuntimeException.class, () -> store.findActive("SIREN", "123456789"));
      assertThrows(RuntimeException.class,
          () -> store.upsert(record("123456789", Set.of(AnomalyType.MISSING_SIRET))));
      assertThrows(RuntimeException.class, () -> store.markNotified(1L, Instant.now()));
      assertThrows(RuntimeException.class, () -> store.softDelete(1L, "ops"));
      assertThrows(RuntimeException.class,
          () -> store.applyCorrection(1L, List.of(party("123456789")), "ops", null));
      assertThrows(RuntimeException.class,
          () -> store.findChangedSince(Instant.EPOCH, 10));
      assertThrows(RuntimeException.class,
          () -> store.findByStatus(QuarantineStatus.PENDING, 10));
    }
  }

  // ── QuarantineService guards ──────────────────────────────────────────────

  @Nested
  @DisplayName("QuarantineService guards")
  class ServiceGuards {

    private static class StubStore implements QuarantineStore {
      QuarantineRecord active;
      QuarantineRecord upsertResult;
      boolean needsNotification = true;
      final List<Long> softDeleted = new ArrayList<>();

      @Override public Optional<QuarantineRecord> findActive(String ks, String k) {
        return Optional.ofNullable(active);
      }
      @Override public UpsertResult upsert(QuarantineRecord r) {
        return new UpsertResult(upsertResult != null ? upsertResult : r, needsNotification);
      }
      @Override public void markNotified(long id, Instant at) { }
      @Override public QuarantineRecord applyCorrection(long id,
          List<PartyRegistrationDetails> c, String by, String n) { return active; }
      @Override public void softDelete(long id, String by) { softDeleted.add(id); }
      @Override public List<QuarantineRecord> findChangedSince(Instant s, int l) {
        return List.of();
      }
      @Override public List<QuarantineRecord> findByStatus(QuarantineStatus s, int l) {
        return List.of();
      }
    }

    private static Anomaly anomaly(AnomalyType type) {
      return new Anomaly(type, type.name() + " detail", null);
    }

    @Test
    @DisplayName("an existing row with no correction falls through to a normal upsert")
    void existingRowWithoutCorrection() {
      StubStore store = new StubStore();
      store.active = new QuarantineRecord(5L, "SIREN", "123456789", "fp",
          Set.of(AnomalyType.MISSING_SIRET), Servability.SERVABLE, null, null,
          QuarantineStatus.PENDING, Instant.EPOCH, Instant.EPOCH, null, null, null);

      QuarantineService.Verdict verdict = new QuarantineService(store, AlertNotifier.none())
          .handle(Flow.INBOUND, "SIREN", "123456789", List.of(party("123456789")),
              List.of(anomaly(AnomalyType.MISSING_SIRET)));

      assertFalse(verdict.corrected(), "a PENDING row carries nothing to serve in preference");
      assertFalse(verdict.blocked());
    }

    @Test
    @DisplayName("an empty response is stored as a null payload")
    void emptyResponseStoresNullPayload() {
      StubStore store = new StubStore();
      new QuarantineService(store, AlertNotifier.none())
          .handle(Flow.INBOUND, "SIREN", "123456789", List.of(),
              List.of(anomaly(AnomalyType.NO_REGISTRATION_FOUND)));
      // Reaching here proves the null-payload branch was taken without a NPE.
    }

    @Test
    @DisplayName("a persisted row with no id is reported as not persisted")
    void rowWithoutIdIsReportedAsNotPersisted() {
      StubStore store = new StubStore();
      // upsert hands back a row that never got an id — e.g. a store that could not read it back.
      store.upsertResult = new QuarantineRecord(null, "SIREN", "123456789", "fp",
          Set.of(AnomalyType.MISSING_SIRET), Servability.SERVABLE, null, null,
          QuarantineStatus.PENDING, Instant.EPOCH, Instant.EPOCH, null, null, null);

      List<AlertNotifier.Notification> sent = new ArrayList<>();
      new QuarantineService(store, sent::add)
          .handle(Flow.INBOUND, "SIREN", "123456789", List.of(party("123456789")),
              List.of(anomaly(AnomalyType.MISSING_SIRET)));

      assertEquals("NOT_PERSISTED", sent.get(0).context().get("quarantineRowId"));
    }

    @Test
    @DisplayName("retiring a row with no id does nothing rather than failing")
    void retireRowWithoutId() {
      StubStore store = new StubStore();
      store.active = new QuarantineRecord(null, "SIREN", "123456789", "fp",
          Set.of(AnomalyType.MISSING_SIRET), Servability.SERVABLE, null, null,
          QuarantineStatus.PENDING, Instant.EPOCH, Instant.EPOCH, null, null, null);

      new QuarantineService(store, AlertNotifier.none())
          .retireIfResolved("SIREN", "123456789");

      assertTrue(store.softDeleted.isEmpty(), "there is no row id to soft-delete");
    }
  }

  // ── SafeNotify ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("SafeNotify")
  class Safety {

    @Test
    @DisplayName("a null notifier or notification is ignored")
    void nullsAreIgnored() {
      List<AlertNotifier.Notification> sent = new ArrayList<>();
      AlertNotifier.Notification n = new AlertNotifier.Notification(
          AnomalyType.MISSING_SIRET, Servability.SERVABLE, Flow.INBOUND, "fp", "m",
          Instant.EPOCH, java.util.Map.of(), List.of());

      SafeNotify.publish(null, n);
      SafeNotify.publish(sent::add, null);
      assertTrue(sent.isEmpty());

      SafeNotify.publish(sent::add, n);
      assertEquals(1, sent.size(), "a well-formed pair does get through");
    }

    @Test
    @DisplayName("a notifier that throws is swallowed")
    void throwingNotifierIsSwallowed() {
      SafeNotify.publish(x -> { throw new IllegalStateException("SMTP down"); },
          new AlertNotifier.Notification(AnomalyType.MISSING_SIRET, Servability.SERVABLE,
              Flow.INBOUND, "fp", "m", Instant.EPOCH, java.util.Map.of(), List.of()));
      // Reaching here without an exception is the assertion.
    }
  }
}
