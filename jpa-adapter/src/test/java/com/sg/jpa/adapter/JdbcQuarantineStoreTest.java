package com.sg.jpa.adapter;

import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.port.out.QuarantineRecord;
import com.sg.domaininterface.port.out.QuarantineStatus;
import com.sg.domaininterface.port.out.QuarantineStore;
import com.sg.domaininterface.port.out.RecordCodec;
import com.sg.domaininterface.rule.party.AnomalyType;
import com.sg.domaininterface.rule.party.Servability;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcQuarantineStore} against a mocked JDBC surface.
 *
 * <p><b>What this does and does not prove.</b> The build may not pull in an embedded database,
 * so these tests drive mocked {@code Connection} / {@code PreparedStatement} / {@code ResultSet}
 * objects. That covers the parts this class is actually responsible for: which statement it
 * issues, which parameters it binds and in what order, how it maps a row back into a
 * {@link QuarantineRecord}, whether it commits or rolls back, and how it translates a
 * {@link SQLException}.
 *
 * <p>It does <em>not</em> prove the SQL parses, or that the column names match the schema. A
 * mock answers whatever it is told to. Catching a typo in the DDL needs an integration
 * environment with a real database, and this file is not a substitute for one.
 */
class JdbcQuarantineStoreTest {

  /** Unique to the row-lock SELECT. */
  private static final String SELECT_FOR_UPDATE = "FOR UPDATE";
  /** Unique to the UPDATE statements — the lock query never contains the table after UPDATE. */
  private static final String UPDATE_STATEMENT = "UPDATE party_registration_quarantine";
  /**
   * Unique to the read-back after a write. Both update paths finish by re-reading the row, so a
   * test that stubs only the UPDATE leaves this one returning null.
   */
  private static final String FIND_BY_ID = "FROM party_registration_quarantine WHERE id = ?";

  private DataSource dataSource;
  private Connection connection;
  private JdbcQuarantineStore store;

  /** Round-trips a marker string so payload handling is observable without a real codec. */
  private static final RecordCodec CODEC = new RecordCodec() {
    @Override public String serialize(List<PartyRegistrationDetails> records) {
      return records == null ? null : "serialized:" + records.size();
    }
    @Override public List<PartyRegistrationDetails> deserialize(String payload) {
      return (payload == null || payload.isBlank()) ? null : List.of(party());
    }
  };

  private static PartyRegistrationDetails party() {
    return new PartyRegistrationDetails(null, null, null, null, null, null,
        "G1", "Acme SA", "ACME", "123456789", "12345678900012", List.of());
  }

  @BeforeEach
  void setUp() throws SQLException {
    dataSource = mock(DataSource.class);
    connection = mock(Connection.class);
    when(dataSource.getConnection()).thenReturn(connection);
    store = new JdbcQuarantineStore(dataSource, CODEC);
  }

  /**
   * Wires a statement whose query returns the given (already-scripted) result set.
   *
   * <p>Pick fragments carefully: the row lock is issued as {@code SELECT ... FOR UPDATE}, so a
   * matcher of {@code contains("UPDATE")} catches the SELECT too and hands back the wrong mock.
   * {@link #SELECT_FOR_UPDATE} and {@link #UPDATE_STATEMENT} are unambiguous.
   */
  private PreparedStatement stubStatement(String sqlFragment, ResultSet rs) throws SQLException {
    PreparedStatement ps = mock(PreparedStatement.class);
    when(connection.prepareStatement(contains(sqlFragment))).thenReturn(ps);
    if (rs != null) {
      when(ps.executeQuery()).thenReturn(rs);
    }
    return ps;
  }

  /** A result set positioned on one fully-populated row, then exhausted. */
  private static ResultSet oneRow(Map<String, Object> values) throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, false);
    when(rs.getLong("id")).thenReturn((Long) values.getOrDefault("id", 7L));
    when(rs.getString("key_space")).thenReturn((String) values.getOrDefault("key_space", "SIREN"));
    when(rs.getString("lookup_key")).thenReturn((String) values.getOrDefault("lookup_key", "123456789"));
    when(rs.getString("anomaly_fingerprint")).thenReturn((String) values.getOrDefault("anomaly_fingerprint", "fp-1"));
    when(rs.getString("anomaly_types")).thenReturn((String) values.getOrDefault("anomaly_types", "MISSING_SIRET"));
    when(rs.getString("servability")).thenReturn((String) values.getOrDefault("servability", "SERVABLE"));
    when(rs.getString("raw_payload")).thenReturn((String) values.getOrDefault("raw_payload", "serialized:1"));
    when(rs.getString("corrected_payload")).thenReturn((String) values.get("corrected_payload"));
    when(rs.getString("status")).thenReturn((String) values.getOrDefault("status", "PENDING"));
    when(rs.getTimestamp("detected_at")).thenReturn((Timestamp) values.get("detected_at"));
    when(rs.getTimestamp("updated_at")).thenReturn((Timestamp) values.get("updated_at"));
    when(rs.getTimestamp("notified_at")).thenReturn((Timestamp) values.get("notified_at"));
    when(rs.getString("corrected_by")).thenReturn((String) values.get("corrected_by"));
    when(rs.getString("notes")).thenReturn((String) values.get("notes"));
    return rs;
  }

  /** A result set standing in for {@code getGeneratedKeys()} — one row carrying the new id. */
  private static ResultSet generatedKey(long id) throws SQLException {
    ResultSet keys = mock(ResultSet.class);
    when(keys.next()).thenReturn(true, false);
    when(keys.getLong(1)).thenReturn(id);
    when(keys.getLong("id")).thenReturn(id);
    return keys;
  }

  /** A result set with no rows. */
  private static ResultSet noRows() throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(false);
    return rs;
  }

  private static QuarantineRecord record(Set<AnomalyType> types,
                                         List<PartyRegistrationDetails> raw) {
    return new QuarantineRecord(null, "SIREN", "123456789", "fp-1", types,
        Servability.SERVABLE, raw, null, QuarantineStatus.PENDING,
        Instant.EPOCH, Instant.EPOCH, null, null, null);
  }

  // ── Reading ───────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("findActive")
  class FindActive {

    @Test
    @DisplayName("binds the key and maps every column back onto the record")
    void mapsAllColumns() throws SQLException {
      Timestamp detected = Timestamp.from(Instant.parse("2026-04-14T10:00:00Z"));
      Timestamp notified = Timestamp.from(Instant.parse("2026-04-14T11:00:00Z"));
      ResultSet rs = oneRow(Map.of(
          "id", 42L, "status", "CORRECTED", "corrected_payload", "serialized:1",
          "detected_at", detected, "notified_at", notified,
          "corrected_by", "ops-user", "notes", "supplied by hand",
          "anomaly_types", "MISSING_SIRET,GOLDEN_PARTY_MISMATCH"));
      PreparedStatement ps = stubStatement("FROM party_registration_quarantine", rs);
      // rs is materialised before stubStatement so no when(...) nests inside another.

      QuarantineRecord found = store.findActive("SIREN", "123456789").orElseThrow();

      verify(ps).setString(1, "SIREN");
      verify(ps).setString(2, "123456789");
      assertEquals(42L, found.id());
      assertEquals("SIREN", found.keySpace());
      assertEquals("fp-1", found.fingerprint());
      assertEquals(Set.of(AnomalyType.MISSING_SIRET, AnomalyType.GOLDEN_PARTY_MISMATCH),
          found.anomalyTypes());
      assertEquals(Servability.SERVABLE, found.servability());
      assertEquals(QuarantineStatus.CORRECTED, found.status());
      assertEquals(detected.toInstant(), found.detectedAt());
      assertEquals(notified.toInstant(), found.notifiedAt());
      assertTrue(found.alreadyNotified());
      assertEquals("ops-user", found.correctedBy());
      assertEquals("supplied by hand", found.notes());
      assertTrue(found.hasUsableCorrection());
    }

    @Test
    @DisplayName("no row yields empty rather than a synthesised record")
    void noRowYieldsEmpty() throws SQLException {
      stubStatement("FROM party_registration_quarantine", noRows());
      assertTrue(store.findActive("SIREN", "000000000").isEmpty());
    }

    @Test
    @DisplayName("a null timestamp column maps to a null instant, not the epoch")
    void nullTimestampsStayNull() throws SQLException {
      ResultSet rs = oneRow(Map.of());
      stubStatement("FROM party_registration_quarantine", rs);
      QuarantineRecord found = store.findActive("SIREN", "123456789").orElseThrow();
      assertNull(found.notifiedAt(), "never notified must not read as notified at epoch");
      assertFalse(found.alreadyNotified());
    }

    @Test
    @DisplayName("a null payload column deserialises to null")
    void nullPayloadStaysNull() throws SQLException {
      Map<String, Object> row = new java.util.HashMap<>();
      row.put("raw_payload", null);
      ResultSet rs = oneRow(row);
      stubStatement("FROM party_registration_quarantine", rs);
      assertNull(store.findActive("SIREN", "123456789").orElseThrow().rawPayload());
    }
  }

  // ── The anomaly-type CSV ──────────────────────────────────────────────────

  @Nested
  @DisplayName("anomaly type column")
  class AnomalyTypes {

    private Set<AnomalyType> readBack(String csv) throws SQLException {
      Map<String, Object> row = new java.util.HashMap<>();
      row.put("anomaly_types", csv);
      ResultSet rs = oneRow(row);
      stubStatement("FROM party_registration_quarantine", rs);
      return store.findActive("SIREN", "123456789").orElseThrow().anomalyTypes();
    }

    @Test
    @DisplayName("several types round-trip")
    void severalTypes() throws SQLException {
      assertEquals(Set.of(AnomalyType.MISSING_SIRET, AnomalyType.MISSING_SIREN),
          readBack("MISSING_SIRET,MISSING_SIREN"));
    }

    @Test
    @DisplayName("null and blank both read as no types recorded")
    void nullAndBlank() throws SQLException {
      assertTrue(readBack(null).isEmpty());
      assertTrue(readBack("   ").isEmpty());
    }

    @Test
    @DisplayName("blank entries between separators are skipped")
    void blankEntriesSkipped() throws SQLException {
      assertEquals(Set.of(AnomalyType.MISSING_SIRET), readBack("MISSING_SIRET,, ,"));
    }

    @Test
    @DisplayName("a name this build no longer knows is skipped, not fatal")
    void unknownNameIsSkipped() throws SQLException {
      assertEquals(Set.of(AnomalyType.MISSING_SIRET),
          readBack("MISSING_SIRET,RETIRED_IN_A_LATER_RELEASE"),
          "a rolling deployment must not make older rows unreadable");
    }

    @Test
    @DisplayName("types are written back as a comma-separated list")
    void typesAreJoinedOnWrite() throws SQLException {
      stubStatement(SELECT_FOR_UPDATE, noRows());
      ResultSet keys = generatedKey(5L);
      PreparedStatement insert = mock(PreparedStatement.class);
      when(connection.prepareStatement(contains("INSERT"), anyInt())).thenReturn(insert);
      when(insert.getGeneratedKeys()).thenReturn(keys);

      store.upsert(record(Set.of(AnomalyType.MISSING_SIRET), List.of(party())));

      verify(insert).setString(eq(4), contains("MISSING_SIRET"));
    }
  }

  // ── Writing ───────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("upsert")
  class Upsert {

    @Test
    @DisplayName("a connection that fails on close reports the upsert as failed")
    void closeFailurePropagates() throws SQLException {
      // The commit already succeeded, so the row is durable, and this still throws. That is the
      // right way round: the caller cannot tell from here whether the failure happened before
      // or after the commit, and treating a connection that would not close as success would
      // hide a pool problem behind a green path.
      stubStatement(SELECT_FOR_UPDATE, noRows());
      ResultSet keys = generatedKey(5L);
      PreparedStatement insert = mock(PreparedStatement.class);
      when(connection.prepareStatement(contains("INSERT"), anyInt())).thenReturn(insert);
      when(insert.getGeneratedKeys()).thenReturn(keys);
      doThrow(new SQLException("connection already returned")).when(connection).close();

      JdbcQuarantineStore.QuarantineStoreException thrown =
          assertThrows(JdbcQuarantineStore.QuarantineStoreException.class,
              () -> store.upsert(record(Set.of(AnomalyType.MISSING_SIRET), List.of(party()))));

      assertEquals("upsert failed", thrown.getMessage());
      verify(connection).commit();
    }

    @Test
    @DisplayName("a correction whose read-back finds nothing fails rather than returning null")
    void correctionVanishingBetweenWriteAndRead() throws SQLException {
      // The update succeeded and the re-read came back empty, which means something deleted the
      // row in between. Returning null would hand the caller a corrected record that no longer
      // exists; failing says so while the cause is still nearby.
      PreparedStatement update = mock(PreparedStatement.class);
      when(connection.prepareStatement(contains(UPDATE_STATEMENT))).thenReturn(update);
      when(update.executeUpdate()).thenReturn(1);
      stubStatement(FIND_BY_ID, noRows());

      assertThrows(NoSuchElementException.class,
          () -> store.applyCorrection(7L, List.of(party()), "ops", "fixed by hand"));
    }

    @Test
    @DisplayName("an insert that yields no generated key still returns a usable record")
    void insertWithoutGeneratedKey() throws SQLException {
      // Some drivers decline to return keys depending on how the statement was prepared. The
      // row is written either way, and the caller needs an answer; a null id is the honest
      // report that the write happened but its key is not known here.
      stubStatement(SELECT_FOR_UPDATE, noRows());
      // Materialised before the when(), because noRows() stubs a mock of its own and Mockito
      // rejects a stubbing that begins inside an unfinished one.
      ResultSet noKeys = noRows();
      PreparedStatement insert = mock(PreparedStatement.class);
      when(connection.prepareStatement(contains("INSERT"), anyInt())).thenReturn(insert);
      when(insert.getGeneratedKeys()).thenReturn(noKeys);

      QuarantineStore.UpsertResult result =
          store.upsert(record(Set.of(AnomalyType.MISSING_SIRET), List.of(party())));

      assertNull(result.record().id(), "no key came back, so none is invented");
      assertTrue(result.needsNotification());
      verify(insert).executeUpdate();
    }

    @Test
    @DisplayName("no existing row inserts, and a brand-new defect always warrants notification")
    void insertsWhenAbsent() throws SQLException {
      stubStatement(SELECT_FOR_UPDATE, noRows());
      ResultSet keys = generatedKey(5L);
      PreparedStatement insert = mock(PreparedStatement.class);
      when(connection.prepareStatement(contains("INSERT"), anyInt())).thenReturn(insert);
      when(insert.getGeneratedKeys()).thenReturn(keys);

      QuarantineStore.UpsertResult result =
          store.upsert(record(Set.of(AnomalyType.MISSING_SIRET), List.of(party())));

      assertTrue(result.needsNotification(), "a defect nobody has seen must be reported");
      verify(insert).setString(1, "SIREN");
      verify(insert).setString(2, "123456789");
      verify(insert).setString(3, "fp-1");
      verify(insert).setString(5, "SERVABLE");
      verify(insert).setString(6, "serialized:1");
      verify(insert).setString(7, "PENDING");
      verify(insert).executeUpdate();
      verify(connection).commit();
    }

    @Test
    @DisplayName("the same defect on an already-notified row does not re-notify")
    void repeatDoesNotReNotify() throws SQLException {
      Timestamp notified = Timestamp.from(Instant.parse("2026-04-14T11:00:00Z"));
      ResultSet existing = oneRow(Map.of(
          "id", 7L, "anomaly_fingerprint", "fp-1", "notified_at", notified));
      stubStatement(SELECT_FOR_UPDATE, existing);
      ResultSet readBack = oneRow(Map.of("id", 7L, "notified_at", notified));
      stubStatement(FIND_BY_ID, readBack);
      PreparedStatement update = mock(PreparedStatement.class);
      when(connection.prepareStatement(contains(UPDATE_STATEMENT))).thenReturn(update);

      QuarantineStore.UpsertResult result =
          store.upsert(record(Set.of(AnomalyType.MISSING_SIRET), List.of(party())));

      assertFalse(result.needsNotification(),
          "the gate is the notified_at column, so it holds across restarts and pods");
      verify(update).executeUpdate();
    }

    @Test
    @DisplayName("a changed fingerprint is a new defect and re-notifies")
    void changedFingerprintReNotifies() throws SQLException {
      Timestamp notified = Timestamp.from(Instant.parse("2026-04-14T11:00:00Z"));
      ResultSet existing = oneRow(Map.of(
          "id", 7L, "anomaly_fingerprint", "fp-OLD", "notified_at", notified));
      stubStatement(SELECT_FOR_UPDATE, existing);
      ResultSet readBack = oneRow(Map.of("id", 7L, "anomaly_fingerprint", "fp-1"));
      stubStatement(FIND_BY_ID, readBack);
      PreparedStatement update = mock(PreparedStatement.class);
      when(connection.prepareStatement(contains(UPDATE_STATEMENT))).thenReturn(update);

      assertTrue(store.upsert(record(Set.of(AnomalyType.MISSING_SIRET), List.of(party())))
          .needsNotification(), "a different bad value is a different problem");
    }

    @Test
    @DisplayName("an existing but un-notified row still warrants notification")
    void unnotifiedRepeatStillNotifies() throws SQLException {
      ResultSet existing = oneRow(Map.of("id", 7L, "anomaly_fingerprint", "fp-1"));
      stubStatement(SELECT_FOR_UPDATE, existing);
      ResultSet readBack = oneRow(Map.of("id", 7L));
      stubStatement(FIND_BY_ID, readBack);
      PreparedStatement update = mock(PreparedStatement.class);
      when(connection.prepareStatement(contains(UPDATE_STATEMENT))).thenReturn(update);

      assertTrue(store.upsert(record(Set.of(AnomalyType.MISSING_SIRET), null))
          .needsNotification(), "the first attempt never got out, so try again");
    }

    @Test
    @DisplayName("a null payload is bound as null rather than the string \"null\"")
    void nullPayloadBoundAsNull() throws SQLException {
      stubStatement(SELECT_FOR_UPDATE, noRows());
      ResultSet keys = generatedKey(5L);
      PreparedStatement insert = mock(PreparedStatement.class);
      when(connection.prepareStatement(contains("INSERT"), anyInt())).thenReturn(insert);
      when(insert.getGeneratedKeys()).thenReturn(keys);

      store.upsert(record(Set.of(AnomalyType.NO_REGISTRATION_FOUND), null));

      verify(insert).setString(6, null);
    }

    @Test
    @DisplayName("the write runs in a transaction and restores autocommit")
    void runsInATransaction() throws SQLException {
      stubStatement(SELECT_FOR_UPDATE, noRows());
      ResultSet keys = generatedKey(5L);
      PreparedStatement insert = mock(PreparedStatement.class);
      when(connection.prepareStatement(contains("INSERT"), anyInt())).thenReturn(insert);
      when(insert.getGeneratedKeys()).thenReturn(keys);

      store.upsert(record(Set.of(AnomalyType.MISSING_SIRET), List.of(party())));

      verify(connection).setAutoCommit(false);
      verify(connection).commit();
      verify(connection).setAutoCommit(true);
      verify(connection, never()).rollback();
    }

    @Test
    @DisplayName("a failure mid-write rolls back rather than leaving a partial row")
    void failureRollsBack() throws SQLException {
      stubStatement(SELECT_FOR_UPDATE, noRows());
      PreparedStatement insert = mock(PreparedStatement.class);
      when(connection.prepareStatement(contains("INSERT"), anyInt())).thenReturn(insert);
      when(insert.executeUpdate()).thenThrow(new SQLException("constraint violated"));

      assertThrows(RuntimeException.class,
          () -> store.upsert(record(Set.of(AnomalyType.MISSING_SIRET), List.of(party()))));

      verify(connection).rollback();
      verify(connection, never()).commit();
      verify(connection).setAutoCommit(true);
    }
  }

  // ── Updates ───────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("updates")
  class Updates {

    @Test
    @DisplayName("markNotified binds the timestamp and the row id")
    void markNotified() throws SQLException {
      PreparedStatement ps = mock(PreparedStatement.class);
      when(connection.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeUpdate()).thenReturn(1);

      Instant at = Instant.parse("2026-04-14T11:00:00Z");
      store.markNotified(7L, at);

      verify(ps).setTimestamp(1, Timestamp.from(at));
      verify(ps).setLong(3, 7L);
      verify(ps).executeUpdate();
    }

    @Test
    @DisplayName("applyCorrection stores the payload, flips the status and reads the row back")
    void applyCorrection() throws SQLException {
      PreparedStatement update = mock(PreparedStatement.class);
      when(connection.prepareStatement(contains(UPDATE_STATEMENT))).thenReturn(update);
      when(update.executeUpdate()).thenReturn(1);
      ResultSet readBack = oneRow(Map.of(
          "id", 7L, "status", "CORRECTED", "corrected_payload", "serialized:1",
          "corrected_by", "ops-user"));
      stubStatement(FIND_BY_ID, readBack);

      QuarantineRecord corrected =
          store.applyCorrection(7L, List.of(party()), "ops-user", "by hand");

      verify(update).setString(1, "serialized:1");
      verify(update).setString(2, "CORRECTED");
      verify(update).setString(3, "ops-user");
      verify(update).setString(4, "by hand");
      verify(update).setLong(6, 7L);
      assertEquals(QuarantineStatus.CORRECTED, corrected.status());
      assertTrue(corrected.hasUsableCorrection());
    }

    @Test
    @DisplayName("correcting a row that is not there is an error, not a silent no-op")
    void applyCorrectionToMissingRow() throws SQLException {
      PreparedStatement update = mock(PreparedStatement.class);
      when(connection.prepareStatement(contains(UPDATE_STATEMENT))).thenReturn(update);
      when(update.executeUpdate()).thenReturn(0);

      assertThrows(RuntimeException.class,
          () -> store.applyCorrection(999L, List.of(party()), "ops", null));
    }

    @Test
    @DisplayName("softDelete records who retired the row")
    void softDelete() throws SQLException {
      PreparedStatement ps = mock(PreparedStatement.class);
      when(connection.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeUpdate()).thenReturn(1);

      store.softDelete(7L, "auto:upstream-resolved");

      verify(ps).setString(1, "SOFT_DELETED");
      verify(ps).setString(2, "auto:upstream-resolved");
      verify(ps).setLong(4, 7L);
    }
  }

  // ── List queries ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("list queries")
  class Queries {

    /** A result set over N identical rows. */
    private ResultSet rows(int count) throws SQLException {
      ResultSet rs = mock(ResultSet.class);
      Boolean[] tail = new Boolean[count];
      java.util.Arrays.fill(tail, true);
      tail[count - 1] = false;
      when(rs.next()).thenReturn(true, tail);
      when(rs.getLong("id")).thenReturn(7L);
      when(rs.getString(anyString())).thenReturn("SIREN");
      when(rs.getString("anomaly_types")).thenReturn("MISSING_SIRET");
      when(rs.getString("servability")).thenReturn("SERVABLE");
      when(rs.getString("status")).thenReturn("PENDING");
      when(rs.getTimestamp(anyString())).thenReturn(null);
      return rs;
    }

    @Test
    @DisplayName("findChangedSince binds the watermark and the limit")
    void findChangedSince() throws SQLException {
      ResultSet rs = rows(3);
      PreparedStatement ps = stubStatement("updated_at", rs);
      Instant since = Instant.parse("2026-04-14T10:00:00Z");

      List<QuarantineRecord> changed = store.findChangedSince(since, 500);

      assertEquals(3, changed.size());
      verify(ps).setTimestamp(1, Timestamp.from(since));
      verify(ps).setInt(2, 500);
    }

    @Test
    @DisplayName("findByStatus binds the status and the limit")
    void findByStatus() throws SQLException {
      ResultSet rs = rows(2);
      PreparedStatement ps = stubStatement("status", rs);

      assertEquals(2, store.findByStatus(QuarantineStatus.PENDING, 100).size());
      verify(ps).setString(1, "PENDING");
      verify(ps).setInt(2, 100);
    }

    @Test
    @DisplayName("no matching rows yields an empty list, not null")
    void emptyResult() throws SQLException {
      stubStatement("updated_at", noRows());
      assertTrue(store.findChangedSince(Instant.EPOCH, 10).isEmpty());
    }
  }

  // ── Failure translation ───────────────────────────────────────────────────

  @Nested
  @DisplayName("failure translation")
  class Failures {

    @Test
    @DisplayName("a dead connection surfaces as the store's own exception on every path")
    void deadConnectionSurfaces() throws SQLException {
      when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

      assertThrows(JdbcQuarantineStore.QuarantineStoreException.class,
          () -> store.findActive("SIREN", "123456789"));
      assertThrows(JdbcQuarantineStore.QuarantineStoreException.class,
          () -> store.upsert(record(Set.of(AnomalyType.MISSING_SIRET), null)));
      assertThrows(JdbcQuarantineStore.QuarantineStoreException.class,
          () -> store.markNotified(1L, Instant.now()));
      assertThrows(JdbcQuarantineStore.QuarantineStoreException.class,
          () -> store.softDelete(1L, "ops"));
      assertThrows(JdbcQuarantineStore.QuarantineStoreException.class,
          () -> store.applyCorrection(1L, List.of(party()), "ops", null));
      assertThrows(JdbcQuarantineStore.QuarantineStoreException.class,
          () -> store.findChangedSince(Instant.EPOCH, 10));
      assertThrows(JdbcQuarantineStore.QuarantineStoreException.class,
          () -> store.findByStatus(QuarantineStatus.PENDING, 10));
    }

    @Test
    @DisplayName("the original SQLException is kept as the cause")
    void causeIsPreserved() throws SQLException {
      SQLException root = new SQLException("connection refused");
      when(dataSource.getConnection()).thenThrow(root);

      JdbcQuarantineStore.QuarantineStoreException e =
          assertThrows(JdbcQuarantineStore.QuarantineStoreException.class,
              () -> store.findActive("SIREN", "123456789"));
      assertSame(root, e.getCause(), "the driver's message is what a DBA needs");
    }

    @Test
    @DisplayName("both collaborators are mandatory")
    void collaboratorsMandatory() {
      assertThrows(NullPointerException.class, () -> new JdbcQuarantineStore(null, CODEC));
      assertThrows(NullPointerException.class, () -> new JdbcQuarantineStore(dataSource, null));
    }
  }

  // ── The multi-type join ───────────────────────────────────────────────────
  // Moved here from the domain's quarantine tests: the subject is this class, and
  // domain cannot see jpa-adapter. A test living a module away from what it tests
  // only works while the boundary is unenforced.

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
