package com.example.invoice.service.alerting.quarantine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.invoice.service.domain.model.Flow;
import com.example.invoice.service.domain.model.PartyRegistrationDetails;
import com.example.invoice.service.domain.port.out.AlertNotifier;
import com.example.invoice.service.domain.port.out.QuarantineRecord;
import com.example.invoice.service.domain.port.out.QuarantineStatus;
import com.example.invoice.service.domain.port.out.QuarantineStore;
import com.example.invoice.service.domain.rule.Anomaly;
import com.example.invoice.service.domain.rule.AnomalyType;
import com.example.invoice.service.domain.rule.Servability;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The conditional arms the main suites do not reach: absent ids, empty payloads, a full poll
 * batch, and the null guards on the notification helper.
 */
class QuarantineEdgePathsTest {

  private static PartyRegistrationDetails party(String siren) {
    return new PartyRegistrationDetails(null, null, null, null, null, null,
        "G1", "Acme SA", "ACME", siren, "12345678900012", List.of());
  }

  private static Anomaly anomaly(AnomalyType type) {
    return new Anomaly(type, type.name() + " detail", null);
  }

  /** A store whose every response is settable, with no database behind it. */
  private static class StubStore implements QuarantineStore {
    QuarantineRecord active;
    QuarantineRecord upsertResult;
    boolean needsNotification = true;
    final List<Long> softDeleted = new ArrayList<>();
    final List<Long> marked = new ArrayList<>();
    final List<QuarantineRecord> upserted = new ArrayList<>();
    List<QuarantineRecord> changed = List.of();

    @Override public Optional<QuarantineRecord> findActive(String ks, String k) {
      return Optional.ofNullable(active);
    }
    @Override public UpsertResult upsert(QuarantineRecord r) {
      upserted.add(r);
      return new UpsertResult(upsertResult != null ? upsertResult : r, needsNotification);
    }
    @Override public void markNotified(long id, Instant at) { marked.add(id); }
    @Override public QuarantineRecord applyCorrection(long id,
        List<PartyRegistrationDetails> c, String by, String n) { return active; }
    @Override public void softDelete(long id, String by) { softDeleted.add(id); }
    @Override public List<QuarantineRecord> findChangedSince(Instant s, int l) { return changed; }
    @Override public List<QuarantineRecord> findByStatus(QuarantineStatus s, int l) {
      return List.of();
    }
  }

  private static QuarantineRecord row(Long id, QuarantineStatus status,
                                      List<PartyRegistrationDetails> corrected) {
    return new QuarantineRecord(id, "SIREN", "123456789", "fp",
        Set.of(AnomalyType.MISSING_SIRET), Servability.SERVABLE, null, corrected,
        status, Instant.EPOCH, Instant.EPOCH, null, null, null);
  }

  // ── QuarantineService guards ──────────────────────────────────────────────

  @Nested
  @DisplayName("QuarantineService")
  class Service {

    @Test
    @DisplayName("an existing row with no correction falls through to a normal upsert")
    void existingRowWithoutCorrection() {
      StubStore store = new StubStore();
      store.active = row(5L, QuarantineStatus.PENDING, null);

      QuarantineService.Verdict verdict = new QuarantineService(store, AlertNotifier.none())
          .handle(Flow.INBOUND, "SIREN", "123456789", List.of(party("123456789")),
              List.of(anomaly(AnomalyType.MISSING_SIRET)));

      assertFalse(verdict.corrected(), "a PENDING row carries nothing to serve in preference");
      assertFalse(verdict.blocked());
    }

    @Test
    @DisplayName("a CORRECTED row with an empty payload is not a usable correction")
    void correctedButEmptyIsNotUsable() {
      StubStore store = new StubStore();
      store.active = row(5L, QuarantineStatus.CORRECTED, List.of());

      assertFalse(new QuarantineService(store, AlertNotifier.none())
          .handle(Flow.INBOUND, "SIREN", "123456789", List.of(party("123456789")),
              List.of(anomaly(AnomalyType.MISSING_SIRET))).corrected());
    }

    @Test
    @DisplayName("a null or empty response is recorded with no payload")
    void absentResponseStoresNullPayload() {
      StubStore store = new StubStore();
      QuarantineService service = new QuarantineService(store, AlertNotifier.none());

      service.handle(Flow.INBOUND, "SIREN", "111111111", null,
          List.of(anomaly(AnomalyType.NO_REGISTRATION_FOUND)));
      assertNull(store.upserted.get(0).rawPayload(), "a null response has nothing to record");

      service.handle(Flow.INBOUND, "SIREN", "222222222", List.of(),
          List.of(anomaly(AnomalyType.NO_REGISTRATION_FOUND)));
      assertNull(store.upserted.get(1).rawPayload(), "…and neither does an empty one");

      service.handle(Flow.INBOUND, "SIREN", "333333333", List.of(party("123456789")),
          List.of(anomaly(AnomalyType.MISSING_SIRET)));
      assertEquals(1, store.upserted.get(2).rawPayload().size(),
          "but a real response is kept so an operator can see what arrived");
    }

    @Test
    @DisplayName("a row that came back without an id is reported as not persisted")
    void rowWithoutIdIsReportedAsNotPersisted() {
      StubStore store = new StubStore();
      store.upsertResult = row(null, QuarantineStatus.PENDING, null);

      List<AlertNotifier.Notification> sent = new ArrayList<>();
      new QuarantineService(store, sent::add)
          .handle(Flow.INBOUND, "SIREN", "123456789", List.of(party("123456789")),
              List.of(anomaly(AnomalyType.MISSING_SIRET)));

      assertEquals("NOT_PERSISTED", sent.get(0).context().get("quarantineRowId"));
      assertTrue(store.marked.isEmpty(),
          "there is no row to mark, and guessing an id would be worse");
    }

    @Test
    @DisplayName("retiring a row with no id does nothing rather than failing")
    void retireRowWithoutId() {
      StubStore store = new StubStore();
      store.active = row(null, QuarantineStatus.PENDING, null);

      new QuarantineService(store, AlertNotifier.none()).retireIfResolved("SIREN", "123456789");

      assertTrue(store.softDeleted.isEmpty(), "there is no row id to soft-delete");
    }

    @Test
    @DisplayName("retiring a row that has an id soft-deletes it")
    void retireRowWithId() {
      StubStore store = new StubStore();
      store.active = row(9L, QuarantineStatus.PENDING, null);

      new QuarantineService(store, AlertNotifier.none()).retireIfResolved("SIREN", "123456789");

      assertEquals(List.of(9L), store.softDeleted);
    }
  }

  // ── SafeNotify ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("SafeNotify")
  class Safety {

    private AlertNotifier.Notification notification() {
      return new AlertNotifier.Notification(AnomalyType.MISSING_SIRET, Servability.SERVABLE,
          Flow.INBOUND, "fp", "m", Instant.EPOCH, Map.of(), List.of());
    }

    @Test
    @DisplayName("a null notifier or a null notification is ignored")
    void nullsAreIgnored() {
      List<AlertNotifier.Notification> sent = new ArrayList<>();

      SafeNotify.publish(null, notification());
      SafeNotify.publish(sent::add, null);
      assertTrue(sent.isEmpty());

      SafeNotify.publish(sent::add, notification());
      assertEquals(1, sent.size(), "a well-formed pair does get through");
    }

    @Test
    @DisplayName("a notifier that throws is swallowed")
    void throwingNotifierIsSwallowed() {
      SafeNotify.publish(x -> { throw new IllegalStateException("SMTP down"); }, notification());
      // Reaching here without an exception is the assertion.
    }
  }

  // ── QuarantinePoller ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("QuarantinePoller")
  class Poller {

    @Test
    @DisplayName("a full batch is still evicted, and the backlog is reported")
    void fullBatchIsEvicted() throws Exception {
      // 500 rows is the poller's batch ceiling; reaching it means more changes are waiting.
      List<QuarantineRecord> full = new ArrayList<>(500);
      for (int i = 0; i < 500; i++) {
        full.add(new QuarantineRecord(1L, "SIREN", "key-" + i, "fp",
            Set.of(AnomalyType.MISSING_SIRET), Servability.SERVABLE, null, null,
            QuarantineStatus.CORRECTED, Instant.EPOCH, Instant.now(), null, null, null));
      }

      StubStore store = new StubStore() {
        volatile boolean served;
        @Override public List<QuarantineRecord> findChangedSince(Instant s, int limit) {
          if (served) return List.of();
          served = true;
          return full;
        }
      };

      CountDownLatch allEvicted = new CountDownLatch(500);
      try (QuarantinePoller poller = new QuarantinePoller(store, Duration.ofMillis(30),
          (ks, key) -> allEvicted.countDown())) {
        poller.start();
        assertTrue(allEvicted.await(5, TimeUnit.SECONDS),
            "every row in a full batch must still be evicted");
      }
    }
  }

  // ── JdbcQuarantineStore: the multi-type join ──────────────────────────────

  @Test
  @DisplayName("several anomaly types are written as one comma-separated value")
  void anomalyTypesAreJoinedWithCommas() throws SQLException {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    when(dataSource.getConnection()).thenReturn(connection);

    ResultSet noRows = mock(ResultSet.class);
    when(noRows.next()).thenReturn(false);
    PreparedStatement select = mock(PreparedStatement.class);
    when(connection.prepareStatement(contains("FOR UPDATE"))).thenReturn(select);
    when(select.executeQuery()).thenReturn(noRows);

    ResultSet keys = mock(ResultSet.class);
    when(keys.next()).thenReturn(true, false);
    when(keys.getLong(1)).thenReturn(5L);
    PreparedStatement insert = mock(PreparedStatement.class);
    when(connection.prepareStatement(contains("INSERT"), anyInt())).thenReturn(insert);
    when(insert.getGeneratedKeys()).thenReturn(keys);

    RecordCodec codec = new RecordCodec() {
      @Override public String serialize(List<PartyRegistrationDetails> r) { return null; }
      @Override public List<PartyRegistrationDetails> deserialize(String p) { return null; }
    };

    new JdbcQuarantineStore(dataSource, codec).upsert(new QuarantineRecord(
        null, "SIREN", "123456789", "fp",
        Set.of(AnomalyType.MISSING_SIRET, AnomalyType.GOLDEN_PARTY_MISMATCH),
        Servability.SERVABLE, null, null, QuarantineStatus.PENDING,
        Instant.EPOCH, Instant.EPOCH, null, null, null));

    // One column, both names, separated — the reader splits on the same comma.
    verify(insert).setString(eq(4), contains(","));
    verify(insert).setString(eq(4), contains("MISSING_SIRET"));
    verify(insert).setString(eq(4), contains("GOLDEN_PARTY_MISMATCH"));
  }
}
