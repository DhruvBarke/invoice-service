package com.example.invoice.service.alerting.quarantine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.invoice.service.domain.model.PartyRegistrationDetails;
import com.example.invoice.service.domain.port.out.QuarantineRecord;
import com.example.invoice.service.domain.port.out.QuarantineStatus;
import com.example.invoice.service.domain.port.out.QuarantineStore;
import com.example.invoice.service.domain.rule.AnomalyType;
import com.example.invoice.service.domain.rule.Servability;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JdbcQuarantineStore} against a real H2 database running the production DDL.
 *
 * <p>Hand-written SQL is only as good as the schema it runs against, so this exercises the
 * statements rather than mocking a {@code Connection} — a mock would assert that the code calls
 * the methods it calls, which says nothing about whether the SQL is valid or the unique index
 * behaves as intended.
 */
class JdbcQuarantineStoreTest {

  private static final AtomicInteger DB_SEQ = new AtomicInteger();

  private DataSource dataSource;
  private JdbcQuarantineStore store;

  /** Comma-joined names in, list back out — enough to round-trip through the CLOB columns. */
  private static final RecordCodec CODEC = new RecordCodec() {
    @Override public String serialize(List<PartyRegistrationDetails> records) {
      if (records == null) return null;
      StringBuilder sb = new StringBuilder();
      for (PartyRegistrationDetails d : records) {
        if (sb.length() > 0) sb.append('|');
        sb.append(d.goldenBdrId()).append(',').append(d.siren());
      }
      return sb.toString();
    }
    @Override public List<PartyRegistrationDetails> deserialize(String payload) {
      if (payload == null || payload.isBlank()) return null;
      List<PartyRegistrationDetails> out = new ArrayList<>();
      for (String row : payload.split("\\|")) {
        String[] parts = row.split(",", -1);
        out.add(new PartyRegistrationDetails(null, null, null, null, null, null,
            parts[0], "name", "MNE", "null".equals(parts[1]) ? null : parts[1], null, List.of()));
      }
      return out;
    }
  };

  @BeforeEach
  void setUp() throws Exception {
    JdbcDataSource ds = new JdbcDataSource();
    // A private in-memory database per test keeps them independent and parallel-safe.
    ds.setURL("jdbc:h2:mem:quarantine" + DB_SEQ.incrementAndGet()
        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    ds.setUser("sa");
    this.dataSource = ds;
    this.store = new JdbcQuarantineStore(ds, CODEC);
    runProductionDdl();
  }

  @AfterEach
  void tearDown() throws Exception {
    try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
      s.execute("DROP ALL OBJECTS");
    }
  }

  /** Runs the shipped migration verbatim — the schema under test is the one that deploys. */
  private void runProductionDdl() throws SQLException {
    Path ddl = Path.of("..", "invoice-service-app", "src", "main", "resources",
        "db", "migration", "V1__party_registration_quarantine.sql");
    String sql;
    try {
      sql = Files.readString(ddl, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read the production DDL at " + ddl, e);
    }
    try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  private static PartyRegistrationDetails party(String golden, String siren) {
    return new PartyRegistrationDetails(null, null, null, null, null, null,
        golden, "Acme SA", "ACME", siren, "12345678900012", List.of());
  }

  private static QuarantineRecord record(String lookupKey, String fingerprint,
                                         Servability servability,
                                         List<PartyRegistrationDetails> raw) {
    return new QuarantineRecord(null, "SIREN", lookupKey, fingerprint,
        Set.of(AnomalyType.MISSING_SIRET), servability, raw, null,
        QuarantineStatus.PENDING,
        Instant.now().truncatedTo(ChronoUnit.MILLIS),
        Instant.now().truncatedTo(ChronoUnit.MILLIS),
        null, null, null);
  }

  // ── Insert and read back ──────────────────────────────────────────────────

  @Test
  @DisplayName("a new defect is inserted and always warrants notification")
  void insertNewDefect() {
    QuarantineStore.UpsertResult result =
        store.upsert(record("123456789", "fp-1", Servability.SERVABLE, List.of(party("G1", "123456789"))));

    assertTrue(result.needsNotification(), "a defect nobody has seen must be reported");
    assertNotNull(result.record().id());

    QuarantineRecord read = store.findActive("SIREN", "123456789").orElseThrow();
    assertEquals("fp-1", read.fingerprint());
    assertEquals(QuarantineStatus.PENDING, read.status());
    assertEquals(Servability.SERVABLE, read.servability());
    assertEquals(Set.of(AnomalyType.MISSING_SIRET), read.anomalyTypes());
    assertEquals("G1", read.rawPayload().get(0).goldenBdrId());
    assertNull(read.notifiedAt(), "nothing has been sent yet");
    assertFalse(read.alreadyNotified());
  }

  @Test
  @DisplayName("a defect with no payload round-trips as a null payload")
  void nullPayloadRoundTrips() {
    store.upsert(record("123456789", "fp-1", Servability.BLOCKING, null));
    QuarantineRecord read = store.findActive("SIREN", "123456789").orElseThrow();
    assertNull(read.rawPayload(),
        "NO_REGISTRATION_FOUND has nothing to store, and that must survive the round trip");
  }

  @Test
  @DisplayName("an unknown key yields empty rather than a synthesised row")
  void unknownKeyIsEmpty() {
    assertTrue(store.findActive("SIREN", "000000000").isEmpty());
  }

  // ── The notify-once gate ──────────────────────────────────────────────────

  @Test
  @DisplayName("the same defect seen again does not warrant a second notification")
  void repeatOfTheSameDefectIsSilent() {
    QuarantineStore.UpsertResult first =
        store.upsert(record("123456789", "fp-1", Servability.SERVABLE, List.of(party("G1", "123456789"))));
    store.markNotified(first.record().id(), Instant.now());

    QuarantineStore.UpsertResult second =
        store.upsert(record("123456789", "fp-1", Servability.SERVABLE, List.of(party("G1", "123456789"))));

    assertFalse(second.needsNotification(),
        "the gate is the notified_at column, so it holds across restarts and across pods");
    assertEquals(first.record().id(), second.record().id(), "the same row is updated, not duplicated");
  }

  @Test
  @DisplayName("an unnotified repeat still warrants notification")
  void unnotifiedRepeatStillNotifies() {
    store.upsert(record("123456789", "fp-1", Servability.SERVABLE, null));
    assertTrue(store.upsert(record("123456789", "fp-1", Servability.SERVABLE, null))
        .needsNotification(), "the first attempt never got out, so try again");
  }

  @Test
  @DisplayName("a changed defect is a new problem and re-notifies")
  void changedFingerprintReNotifies() {
    QuarantineStore.UpsertResult first =
        store.upsert(record("123456789", "fp-1", Servability.SERVABLE, null));
    store.markNotified(first.record().id(), Instant.now());

    QuarantineStore.UpsertResult changed =
        store.upsert(record("123456789", "fp-2", Servability.SERVABLE, null));

    assertTrue(changed.needsNotification(), "a different bad value is a different defect");
    assertEquals("fp-2", store.findActive("SIREN", "123456789").orElseThrow().fingerprint());
  }

  @Test
  @DisplayName("marking notified is what closes the gate")
  void markNotifiedClosesTheGate() {
    QuarantineRecord row = store.upsert(record("123456789", "fp-1", Servability.SERVABLE, null)).record();
    Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    store.markNotified(row.id(), at);

    assertTrue(store.findActive("SIREN", "123456789").orElseThrow().alreadyNotified());
  }

  // ── Corrections ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("a correction is stored, flips the status, and is readable back")
  void applyCorrection() {
    QuarantineRecord row = store.upsert(
        record("123456789", "fp-1", Servability.BLOCKING, null)).record();

    QuarantineRecord corrected = store.applyCorrection(row.id(),
        List.of(party("G9", "987654321")), "ops-user", "supplied by hand");

    assertEquals(QuarantineStatus.CORRECTED, corrected.status());
    assertEquals("ops-user", corrected.correctedBy());
    assertEquals("supplied by hand", corrected.notes());
    assertTrue(corrected.hasUsableCorrection());
    assertEquals("987654321", corrected.correctedPayload().get(0).siren());

    QuarantineRecord read = store.findActive("SIREN", "123456789").orElseThrow();
    assertTrue(read.hasUsableCorrection(), "the correction outranks the referential from now on");
  }

  @Test
  @DisplayName("a changed defect discards the correction written against the old value")
  void changedDefectDiscardsTheCorrection() {
    QuarantineRecord row = store.upsert(
        record("123456789", "fp-1", Servability.BLOCKING, null)).record();
    store.applyCorrection(row.id(), List.of(party("G9", "987654321")), "ops", null);

    store.upsert(record("123456789", "fp-2", Servability.BLOCKING, null));

    QuarantineRecord read = store.findActive("SIREN", "123456789").orElseThrow();
    assertFalse(read.hasUsableCorrection(),
        "a correction for a value that has since changed would be wrong to serve");
  }

  // ── Soft delete and history ───────────────────────────────────────────────

  @Test
  @DisplayName("a soft-deleted row leaves the key free without losing the history")
  void softDeleteRetiresTheRow() {
    QuarantineRecord row = store.upsert(
        record("123456789", "fp-1", Servability.SERVABLE, null)).record();

    store.softDelete(row.id(), "auto:upstream-resolved");

    assertTrue(store.findActive("SIREN", "123456789").isEmpty(),
        "the row is retired, so the referential value flows again");

    QuarantineStore.UpsertResult fresh =
        store.upsert(record("123456789", "fp-3", Servability.SERVABLE, null));
    assertTrue(fresh.needsNotification());
    assertFalse(fresh.record().id().equals(row.id()),
        "a new row is created — NULL never collides in the unique index, so history accumulates");
  }

  // ── Queries the poller and console use ────────────────────────────────────

  @Test
  @DisplayName("findChangedSince drives cross-instance eviction")
  void findChangedSince() {
    Instant before = Instant.now().minusSeconds(60);
    store.upsert(record("111111111", "fp-1", Servability.SERVABLE, null));
    store.upsert(record("222222222", "fp-2", Servability.SERVABLE, null));

    assertEquals(2, store.findChangedSince(before, 10).size());
    assertEquals(1, store.findChangedSince(before, 1).size(), "the limit is honoured");
    assertTrue(store.findChangedSince(Instant.now().plusSeconds(60), 10).isEmpty());
  }

  @Test
  @DisplayName("findByStatus backs the operator console")
  void findByStatus() {
    QuarantineRecord pending = store.upsert(
        record("111111111", "fp-1", Servability.SERVABLE, null)).record();
    store.upsert(record("222222222", "fp-2", Servability.BLOCKING, null));
    store.applyCorrection(pending.id(), List.of(party("G9", "987654321")), "ops", null);

    assertEquals(1, store.findByStatus(QuarantineStatus.PENDING, 10).size());
    assertEquals(1, store.findByStatus(QuarantineStatus.CORRECTED, 10).size());
    assertTrue(store.findByStatus(QuarantineStatus.SOFT_DELETED, 10).isEmpty());
  }

  @Test
  @DisplayName("rows for different keys are independent")
  void keysAreIndependent() {
    store.upsert(record("111111111", "fp-1", Servability.SERVABLE, null));
    store.upsert(record("222222222", "fp-2", Servability.BLOCKING, null));

    assertEquals("fp-1", store.findActive("SIREN", "111111111").orElseThrow().fingerprint());
    assertEquals("fp-2", store.findActive("SIREN", "222222222").orElseThrow().fingerprint());
  }

  // ── Failure surface ───────────────────────────────────────────────────────

  @Test
  @DisplayName("a broken DataSource surfaces as the store's own exception type")
  void brokenDataSourceSurfaces() {
    JdbcDataSource broken = new JdbcDataSource();
    broken.setURL("jdbc:h2:mem:nonexistent;IFEXISTS=TRUE");
    broken.setUser("sa");
    JdbcQuarantineStore failing = new JdbcQuarantineStore(broken, CODEC);

    assertThrows(RuntimeException.class, () -> failing.findActive("SIREN", "123456789"));
  }

  @Test
  @DisplayName("both collaborators are mandatory")
  void collaboratorsMandatory() {
    assertThrows(NullPointerException.class, () -> new JdbcQuarantineStore(null, CODEC));
    assertThrows(NullPointerException.class, () -> new JdbcQuarantineStore(dataSource, null));
  }
}
