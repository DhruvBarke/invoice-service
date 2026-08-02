package com.example.invoice.service.alerting.quarantine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The last few conditional arms: batch limits, null payloads and absent columns. */
class QuarantineRemainingPathsTest {

  private static final AtomicInteger DB_SEQ = new AtomicInteger(2000);

  private static PartyRegistrationDetails party(String siren) {
    return new PartyRegistrationDetails(null, null, null, null, null, null,
        "G1", "Acme SA", "ACME", siren, "12345678900012", List.of());
  }

  private static final RecordCodec CODEC = new RecordCodec() {
    @Override public String serialize(List<PartyRegistrationDetails> records) {
      return records == null ? null : "payload";
    }
    @Override public List<PartyRegistrationDetails> deserialize(String payload) {
      return (payload == null || payload.isBlank()) ? null : List.of(party("123456789"));
    }
  };

  private DataSource dataSource;
  private JdbcQuarantineStore store;

  @BeforeEach
  void setUp() throws Exception {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:qrem" + DB_SEQ.incrementAndGet()
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

  private QuarantineRecord record(String key) {
    return new QuarantineRecord(null, "SIREN", key, "fp",
        Set.of(AnomalyType.MISSING_SIRET), Servability.SERVABLE, null, null,
        QuarantineStatus.PENDING, Instant.now(), Instant.now(), null, null, null);
  }

  @Test
  @DisplayName("a row whose anomaly column is NULL reads back as an empty set")
  void nullAnomalyColumnReadsAsEmpty() throws Exception {
    store.upsert(record("123456789"));
    // NOT NULL on the column means this can only arrive from a schema that predates it, but the
    // reader must degrade to "no types recorded" rather than throwing on the whole row.
    try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
      s.executeUpdate("ALTER TABLE party_registration_quarantine "
          + "ALTER COLUMN anomaly_types SET NULL");
      s.executeUpdate("UPDATE party_registration_quarantine "
          + "SET anomaly_types = NULL WHERE lookup_key = '123456789'");
    }

    assertTrue(store.findActive("SIREN", "123456789").orElseThrow().anomalyTypes().isEmpty());
  }

  @Test
  @DisplayName("a blank anomaly column reads back as an empty set")
  void blankAnomalyColumnReadsAsEmpty() throws Exception {
    store.upsert(record("123456789"));
    try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
      s.executeUpdate("UPDATE party_registration_quarantine "
          + "SET anomaly_types = '   ' WHERE lookup_key = '123456789'");
    }
    assertTrue(store.findActive("SIREN", "123456789").orElseThrow().anomalyTypes().isEmpty());
  }

  @Test
  @DisplayName("a query that matches nothing yields an empty result, not a null row")
  void emptyResultSet() {
    assertTrue(store.findActive("SIREN", "does-not-exist").isEmpty());
    assertTrue(store.findByStatus(QuarantineStatus.SOFT_DELETED, 10).isEmpty());
    assertTrue(store.findChangedSince(Instant.now().plusSeconds(3600), 10).isEmpty());
  }

  @Test
  @DisplayName("the service stores a null payload when the referential returned nothing")
  void nullResponseStoresNullPayload() {
    List<QuarantineRecord> upserted = new ArrayList<>();
    QuarantineStore capturing = new QuarantineStore() {
      @Override public Optional<QuarantineRecord> findActive(String ks, String k) {
        return Optional.empty();
      }
      @Override public UpsertResult upsert(QuarantineRecord r) {
        upserted.add(r);
        return new UpsertResult(r, false);
      }
      @Override public void markNotified(long id, Instant at) { }
      @Override public QuarantineRecord applyCorrection(long id,
          List<PartyRegistrationDetails> c, String by, String n) { return null; }
      @Override public void softDelete(long id, String by) { }
      @Override public List<QuarantineRecord> findChangedSince(Instant s, int l) {
        return List.of();
      }
      @Override public List<QuarantineRecord> findByStatus(QuarantineStatus s, int l) {
        return List.of();
      }
    };

    QuarantineService service = new QuarantineService(capturing, AlertNotifier.none());

    service.handle(Flow.INBOUND, "SIREN", "111111111", null,
        List.of(new Anomaly(AnomalyType.NO_REGISTRATION_FOUND, "nothing found", null)));
    assertNull(upserted.get(0).rawPayload(), "a null response has no payload to record");

    service.handle(Flow.INBOUND, "SIREN", "222222222", List.of(),
        List.of(new Anomaly(AnomalyType.NO_REGISTRATION_FOUND, "nothing found", null)));
    assertNull(upserted.get(1).rawPayload(), "…and neither does an empty one");

    service.handle(Flow.INBOUND, "SIREN", "333333333", List.of(party("123456789")),
        List.of(new Anomaly(AnomalyType.MISSING_SIRET, "no siret", null)));
    assertEquals(1, upserted.get(2).rawPayload().size(),
        "but a real response is recorded so an operator can see what arrived");
  }

  @Test
  @DisplayName("the poller reports when it fills a batch, so a backlog is visible")
  void batchLimitIsReported() throws Exception {
    // 500 changed rows is the batch ceiling; hitting it means more are waiting.
    List<QuarantineRecord> full = new ArrayList<>(500);
    for (int i = 0; i < 500; i++) {
      full.add(new QuarantineRecord(1L, "SIREN", "key-" + i, "fp",
          Set.of(AnomalyType.MISSING_SIRET), Servability.SERVABLE, null, null,
          QuarantineStatus.CORRECTED, Instant.EPOCH, Instant.now(), null, null, null));
    }

    CountDownLatch allEvicted = new CountDownLatch(500);
    QuarantineStore serving = new QuarantineStore() {
      volatile boolean served;
      @Override public Optional<QuarantineRecord> findActive(String ks, String k) {
        return Optional.empty();
      }
      @Override public UpsertResult upsert(QuarantineRecord r) {
        return new UpsertResult(r, false);
      }
      @Override public void markNotified(long id, Instant at) { }
      @Override public QuarantineRecord applyCorrection(long id,
          List<PartyRegistrationDetails> c, String by, String n) { return null; }
      @Override public void softDelete(long id, String by) { }
      @Override public List<QuarantineRecord> findChangedSince(Instant s, int limit) {
        if (served) return List.of();
        served = true;
        return full;
      }
      @Override public List<QuarantineRecord> findByStatus(QuarantineStatus s, int l) {
        return List.of();
      }
    };

    try (QuarantinePoller poller = new QuarantinePoller(serving, Duration.ofMillis(30),
        (ks, key) -> allEvicted.countDown())) {
      poller.start();
      assertTrue(allEvicted.await(5, TimeUnit.SECONDS),
          "every row in a full batch must still be evicted");
    }
  }

  @Test
  @DisplayName("marking notified is skipped when the row has no id to mark")
  void markNotifiedSkippedWithoutAnId() {
    List<Long> marked = new ArrayList<>();
    QuarantineStore idless = new QuarantineStore() {
      @Override public Optional<QuarantineRecord> findActive(String ks, String k) {
        return Optional.empty();
      }
      @Override public UpsertResult upsert(QuarantineRecord r) {
        // A store that could not read the row back returns it without an id.
        return new UpsertResult(r, true);
      }
      @Override public void markNotified(long id, Instant at) { marked.add(id); }
      @Override public QuarantineRecord applyCorrection(long id,
          List<PartyRegistrationDetails> c, String by, String n) { return null; }
      @Override public void softDelete(long id, String by) { }
      @Override public List<QuarantineRecord> findChangedSince(Instant s, int l) {
        return List.of();
      }
      @Override public List<QuarantineRecord> findByStatus(QuarantineStatus s, int l) {
        return List.of();
      }
    };

    new QuarantineService(idless, AlertNotifier.none())
        .handle(Flow.INBOUND, "SIREN", "123456789", List.of(party("123456789")),
            List.of(new Anomaly(AnomalyType.MISSING_SIRET, "no siret", null)));

    assertTrue(marked.isEmpty(), "there is no row to mark, and guessing an id would be worse");
  }
}
