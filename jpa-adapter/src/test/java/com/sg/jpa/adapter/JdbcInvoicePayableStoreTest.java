package com.sg.jpa.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.model.einvoice.error.ErrorCode;
import com.sg.domaininterface.model.einvoice.error.LifecycleEventType;
import com.sg.domaininterface.model.einvoice.error.MappingError;
import com.sg.domaininterface.model.einvoice.error.RegistrationOutcome;
import com.sg.domaininterface.model.payableinvoice.InvoiceDocumentPayable;
import com.sg.domaininterface.model.payableinvoice.InvoiceItem;
import com.sg.domaininterface.model.payableinvoice.InvoicePayable;
import com.sg.domaininterface.model.payableinvoice.InvoicePayableModel;
import com.sg.domaininterface.port.einvoice.InvoicePayableStore.PersistRequest;
import com.sg.domaininterface.port.einvoice.LifecycleEventPublisher.PendingLifecycleEvent;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The statements this store issues and the parameters it binds.
 *
 * <p><b>What a mocked JDBC surface proves, and what it does not.</b> These tests drive mock
 * {@link Connection} / {@link PreparedStatement} objects, so they establish which statement is
 * chosen, in what order, with which parameters, whether the work commits or rolls back, and how
 * a {@link SQLException} is translated. They establish nothing about whether the SQL parses or
 * whether the column names match the schema — a mock answers whatever it is told. Catching a
 * typo in the DDL needs an environment with a real database, and nothing here should be read as
 * a substitute for that.
 *
 * <p>Matchers are chosen to be unambiguous against every statement this class issues. Matching
 * on {@code "INSERT"} alone would hit three of them, and the mock returned for the wrong one
 * fails later and somewhere else.
 */
class JdbcInvoicePayableStoreTest {

  /** Unique to the sequence read. */
  private static final String NEXTVAL = "nextval";
  private static final String INSERT_PAYABLE = "INTO publicinvoice.t_invoice_payable";
  private static final String INSERT_ITEMS = "INTO publicinvoice.t_invoice_items";
  private static final String INSERT_DOCUMENTS = "INTO publicinvoice.t_invoice_document_payable";
  private static final String UPDATE_LIFECYCLE = "UPDATE publicinvoice.t_invoice_payable";

  private DataSource dataSource;
  private Connection connection;
  private PreparedStatement sequence;
  private PreparedStatement payable;
  private PreparedStatement items;
  private PreparedStatement documents;
  private JdbcInvoicePayableStore store;

  @BeforeEach
  void setUp() throws SQLException {
    dataSource = mock(DataSource.class);
    connection = mock(Connection.class);
    when(dataSource.getConnection()).thenReturn(connection);

    ResultSet seqRow = mock(ResultSet.class);
    when(seqRow.next()).thenReturn(true);
    when(seqRow.getLong(1)).thenReturn(1000042L);

    sequence = mock(PreparedStatement.class);
    when(sequence.executeQuery()).thenReturn(seqRow);
    when(connection.prepareStatement(contains(NEXTVAL))).thenReturn(sequence);

    payable = mock(PreparedStatement.class);
    items = mock(PreparedStatement.class);
    documents = mock(PreparedStatement.class);
    when(connection.prepareStatement(contains(INSERT_PAYABLE))).thenReturn(payable);
    when(connection.prepareStatement(contains(INSERT_ITEMS))).thenReturn(items);
    when(connection.prepareStatement(contains(INSERT_DOCUMENTS))).thenReturn(documents);

    store = new JdbcInvoicePayableStore(dataSource);
  }

  // ── Fixtures ──────────────────────────────────────────────────────────────

  private static InvoicePayableModel model(String providerReference) {
    InvoicePayable p = new InvoicePayable();
    p.setProviderReference(providerReference);

    InvoicePayableModel m = new InvoicePayableModel();
    m.setInvoicePayable(p);
    m.setSgEntity("SG-FR");
    m.setFeeCategory("CUSTODY");
    m.setProviderId("PRV-1");
    m.setInvoiceDate(LocalDate.of(2026, 1, 15));
    m.setTradingStartDate(LocalDate.of(2026, 1, 1));
    m.setTradingEndDate(LocalDate.of(2026, 1, 31));
    m.setRefCptyId("CPTY-9");
    m.setInvoiceType("380");
    m.setAmount(new BigDecimal("1234.56"));
    m.setCurrency("EUR");
    m.setCreatedByUser("einvoice");
    m.setLastUpdatedByUser("einvoice");
    return m;
  }

  private static PersistRequest request(InvoicePayableModel m, List<InvoiceItem> lines,
                                        List<InvoiceDocumentPayable> docs,
                                        RegistrationOutcome outcome) {
    return new PersistRequest(Business.MARK, "F01", "CUSTODY", "EINVOICE", m, lines, docs,
        outcome);
  }

  private static RegistrationOutcome clean() {
    return RegistrationOutcome.decide(List.of());
  }

  // ── The envelope ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("the payable row")
  class Envelope {

    @Test
    @DisplayName("the reference is minted from the sequence, zero-padded, and stamped everywhere")
    void referenceIsMintedAndStamped() throws SQLException {
      InvoicePayableModel m = model("SUP-INV-1");
      InvoiceItem line = new InvoiceItem();
      InvoiceDocumentPayable doc = new InvoiceDocumentPayable();

      UUID id = store.persist(request(m, List.of(line), List.of(doc), clean()));

      // The value the sequence gave, formatted — not the supplier's id, which is only unique
      // within the supplier that issued it.
      assertEquals("0001000042", m.getInvoiceReference());
      assertEquals("0001000042", line.getInvReferenceSg(),
          "the lines correlate on SG's reference, and only the store knows it");
      assertEquals("0001000042", doc.getInvoiceReference());
      assertEquals(id, m.getId(), "the caller gets back the id the row was written with");
      verify(payable).setString(2, "0001000042");
    }

    @Test
    @DisplayName("mapped header fields are written, not dropped")
    void headerFieldsAreWritten() throws SQLException {
      store.persist(request(model("SUP-INV-1"), List.of(), List.of(), clean()));

      verify(payable).setString(3, "SG-FR");
      verify(payable).setString(4, "CUSTODY");
      verify(payable).setString(5, "PRV-1");
      verify(payable).setObject(11, LocalDate.of(2026, 1, 15));
      verify(payable).setObject(12, LocalDate.of(2026, 1, 1));
      verify(payable).setObject(13, LocalDate.of(2026, 1, 31));
      verify(payable).setString(14, "CPTY-9");
      verify(payable).setString(15, "380");
      verify(payable).setBigDecimal(17, new BigDecimal("1234.56"));
      verify(payable).setString(18, "EUR");
    }

    @Test
    @DisplayName("the supplier's id lands on provider_reference, which the duplicate check reads")
    void providerReferenceIsHoisted() throws SQLException {
      store.persist(request(model("SUP-INV-1"), List.of(), List.of(), clean()));
      verify(payable).setString(21, "SUP-INV-1");
    }

    @Test
    @DisplayName("the status is the decided outcome, not a value from the sender")
    void statusComesFromTheOutcome() throws SQLException {
      RegistrationOutcome refused = RegistrationOutcome.decide(
          new java.util.ArrayList<>(List.of(
              MappingError.of(ErrorCode.DUPLICATE_INVOICE, "seen before"))));

      store.persist(request(model("SUP-INV-1"), List.of(), List.of(), refused));

      verify(payable).setString(16, refused.status().name());
      verify(payable).setString(eq(26), contains("DUPLICATE_INVOICE"));
    }

    @Test
    @DisplayName("a null model still writes a row, with the payload as an empty object")
    void nullModelStillWritesARow() throws SQLException {
      // Mapping can fail outright. The registration is still a fact worth recording, and the
      // payload column is NOT NULL.
      assertNotNull(store.persist(request(null, List.of(), List.of(), clean())));

      verify(payable).setString(6, "{}");
      verify(payable).setString(3, null);
      verify(payable).setString(21, null);
      verify(payable).setBoolean(19, false);
    }

    @Test
    @DisplayName("a model with no nested payable has no provider reference to hoist")
    void modelWithoutPayable() throws SQLException {
      InvoicePayableModel bare = new InvoicePayableModel();
      store.persist(request(bare, List.of(), List.of(), clean()));
      verify(payable).setString(21, null);
    }

    @Test
    @DisplayName("a soft-deleted model and an unresolved business are written as they are")
    void deletedFlagAndAbsentBusiness() throws SQLException {
      InvoicePayableModel deleted = model("SUP-INV-1");
      deleted.setDeleted(true);

      // A null business means the marker never resolved one. Recording it as null is what lets
      // an operator find the invoices no rule set ever ran against.
      store.persist(new PersistRequest(null, null, null, "EINVOICE", deleted,
          List.of(), List.of(), clean()));

      verify(payable).setBoolean(19, true);
      verify(payable).setString(22, null);
    }

    @Test
    @DisplayName("absent dates and amounts bind as typed nulls, not as empty strings")
    void absentValuesBindAsTypedNulls() throws SQLException {
      InvoicePayableModel sparse = new InvoicePayableModel();
      sparse.setInvoicePayable(new InvoicePayable());

      store.persist(request(sparse, List.of(), List.of(), clean()));

      verify(payable).setNull(11, Types.DATE);
      verify(payable).setNull(17, Types.DECIMAL);
    }
  }

  // ── Lines and documents ───────────────────────────────────────────────────

  @Nested
  @DisplayName("lines and documents")
  class Children {

    @Test
    @DisplayName("no lines and no documents means those statements are never prepared")
    void emptyChildrenSkipTheirStatements() throws SQLException {
      store.persist(request(model("SUP-INV-1"), List.of(), List.of(), clean()));

      verify(connection, never()).prepareStatement(contains(INSERT_ITEMS));
      verify(connection, never()).prepareStatement(contains(INSERT_DOCUMENTS));
    }

    @Test
    @DisplayName("lines are batched, and a line with no id of its own is given one")
    void linesAreBatched() throws SQLException {
      InvoiceItem withId = new InvoiceItem();
      withId.setInvoiceItemId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
      withId.setItemDescription("CUSTODY FEE");
      withId.setFeeAmount(new BigDecimal("50.00"));
      withId.setFeeCurrency("EUR");

      InvoiceItem withoutId = new InvoiceItem();

      store.persist(request(model("SUP-INV-1"), List.of(withId, withoutId), List.of(), clean()));

      verify(items, times(2)).addBatch();
      verify(items).executeBatch();
      verify(items).setObject(1, UUID.fromString("11111111-1111-1111-1111-111111111111"));
      verify(items).setString(22, "CUSTODY FEE");
      verify(items).setBigDecimal(9, new BigDecimal("50.00"));
      // The row needs a key whether or not the mapper produced one.
      verify(items, times(2)).setObject(eq(1), any(UUID.class));
    }

    @Test
    @DisplayName("a line with no dates falls back to today rather than writing null")
    void lineDatesDefaultToToday() throws SQLException {
      store.persist(request(model("SUP-INV-1"), List.of(new InvoiceItem()), List.of(), clean()));

      verify(items).setObject(18, LocalDate.now());
      verify(items).setObject(20, LocalDate.now());
    }

    @Test
    @DisplayName("a line that carries its own dates keeps them")
    void lineDatesAreKeptWhenSupplied() throws SQLException {
      InvoiceItem dated = new InvoiceItem();
      dated.setItemsCreationDate(LocalDate.of(2020, 3, 4));
      dated.setItemsLastUpdateDate(LocalDate.of(2021, 5, 6));

      store.persist(request(model("SUP-INV-1"), List.of(dated), List.of(), clean()));

      verify(items).setObject(18, LocalDate.of(2020, 3, 4));
      verify(items).setObject(20, LocalDate.of(2021, 5, 6));
    }

    @Test
    @DisplayName("documents are batched with their channel and no content column")
    void documentsAreBatched() throws SQLException {
      InvoiceDocumentPayable doc = InvoiceDocumentPayable.builder()
          .documentName("invoice.pdf")
          .documentType("PDF")
          .format("application/pdf")
          .incomingLine("MULTIPART")
          .build();

      store.persist(request(model("SUP-INV-1"), List.of(), List.of(doc), clean()));

      verify(documents).addBatch();
      verify(documents).executeBatch();
      verify(documents).setString(4, "invoice.pdf");
      verify(documents).setString(5, "PDF");
      verify(documents).setString(7, "MULTIPART");
      // Null until an upload returns a handle — the honest record that the content is not yet
      // retrievable, which is different from no document having arrived.
      verify(documents).setString(3, null);
    }

    @Test
    @DisplayName("an absent registration status binds as a typed null, not as false")
    void nullRegistrationStatus() throws SQLException {
      // false would claim the registration was attempted and failed. Null says it has not been
      // attempted, and the two lead an operator to different places.
      store.persist(request(model("SUP-INV-1"), List.of(),
          List.of(new InvoiceDocumentPayable()), clean()));

      verify(documents).setNull(12, Types.BOOLEAN);
      verify(documents).setNull(anyInt(), eq(Types.BOOLEAN));
    }

    @Test
    @DisplayName("a document that already has an id keeps it")
    void documentKeepsItsOwnId() throws SQLException {
      UUID given = UUID.fromString("22222222-2222-2222-2222-222222222222");
      InvoiceDocumentPayable doc = new InvoiceDocumentPayable();
      doc.setId(given);

      store.persist(request(model("SUP-INV-1"), List.of(), List.of(doc), clean()));

      verify(documents).setObject(1, given);
    }

    @Test
    @DisplayName("a supplied registration status is written as given")
    void suppliedRegistrationStatus() throws SQLException {
      InvoiceDocumentPayable doc = new InvoiceDocumentPayable();
      doc.setRegistrationStatus(Boolean.TRUE);
      doc.setCreatedDate(LocalDateTime.of(2026, 2, 3, 4, 5));
      doc.setLastUpdatedDate(LocalDateTime.of(2026, 2, 3, 4, 6));

      store.persist(request(model("SUP-INV-1"), List.of(), List.of(doc), clean()));

      verify(documents).setBoolean(12, true);
      verify(documents).setTimestamp(eq(19), any());
      verify(documents).setTimestamp(eq(20), any());
    }

    @Test
    @DisplayName("over-long text is truncated to the column width rather than failing the insert")
    void longTextIsTruncated() throws SQLException {
      InvoiceDocumentPayable doc = new InvoiceDocumentPayable();
      doc.setDocumentName("x".repeat(600));

      store.persist(request(model("SUP-INV-1"), List.of(), List.of(doc), clean()));

      verify(documents).setString(eq(4), eq("x".repeat(512)));
    }
  }

  // ── Transaction handling ──────────────────────────────────────────────────

  @Nested
  @DisplayName("the transaction")
  class Transaction {

    @Test
    @DisplayName("the three inserts commit together and autocommit is restored")
    void commitsAndRestoresAutoCommit() throws SQLException {
      store.persist(request(model("SUP-INV-1"), List.of(new InvoiceItem()),
          List.of(new InvoiceDocumentPayable()), clean()));

      verify(connection).setAutoCommit(false);
      verify(connection).commit();
      verify(connection).setAutoCommit(true);
      verify(connection, never()).rollback();
    }

    @Test
    @DisplayName("a failure part-way rolls back, so no half-written registration survives")
    void rollsBackOnFailure() throws SQLException {
      when(items.executeBatch()).thenThrow(new SQLException("constraint violated"));

      JdbcInvoicePayableStore.PersistenceException thrown =
          assertThrows(JdbcInvoicePayableStore.PersistenceException.class,
              () -> store.persist(request(model("SUP-INV-1"), List.of(new InvoiceItem()),
                  List.of(), clean())));

      verify(connection).rollback();
      verify(connection, never()).commit();
      verify(connection).setAutoCommit(true);
      assertNotNull(thrown.getCause());
    }

    @Test
    @DisplayName("a runtime failure rolls back too, not only a SQLException")
    void rollsBackOnRuntimeFailure() throws SQLException {
      when(payable.executeUpdate()).thenThrow(new IllegalStateException("driver bug"));

      assertThrows(IllegalStateException.class,
          () -> store.persist(request(model("SUP-INV-1"), List.of(), List.of(), clean())));

      verify(connection).rollback();
      verify(connection, never()).commit();
    }

    @Test
    @DisplayName("a sequence that yields nothing is a persistence failure, not a null reference")
    void emptySequenceFails() throws SQLException {
      ResultSet empty = mock(ResultSet.class);
      when(empty.next()).thenReturn(false);
      when(sequence.executeQuery()).thenReturn(empty);

      assertThrows(JdbcInvoicePayableStore.PersistenceException.class,
          () -> store.persist(request(model("SUP-INV-1"), List.of(), List.of(), clean())));

      verify(connection).rollback();
    }

    @Test
    @DisplayName("an unavailable connection surfaces as a persistence failure")
    void unavailableConnection() throws SQLException {
      when(dataSource.getConnection()).thenThrow(new SQLException("pool exhausted"));

      JdbcInvoicePayableStore.PersistenceException thrown =
          assertThrows(JdbcInvoicePayableStore.PersistenceException.class,
              () -> store.persist(request(model("SUP-INV-1"), List.of(), List.of(), clean())));

      assertTrue(thrown.getMessage().contains("failed to persist"));
    }
  }

  // ── Lifecycle publishing ──────────────────────────────────────────────────

  @Nested
  @DisplayName("publishing a lifecycle event")
  class Lifecycle {

    private PreparedStatement update;

    @BeforeEach
    void stubUpdate() throws SQLException {
      update = mock(PreparedStatement.class);
      when(connection.prepareStatement(contains(UPDATE_LIFECYCLE))).thenReturn(update);
    }

    private PendingLifecycleEvent event(UUID id) {
      return new PendingLifecycleEvent(id, "0001000042", LifecycleEventType.REFUSED,
          "DOUBLON", "already registered", Instant.EPOCH);
    }

    @Test
    @DisplayName("the row is marked PENDING with its reason, for the scheduler to drain")
    void marksPending() throws SQLException {
      UUID id = UUID.randomUUID();
      when(update.executeUpdate()).thenReturn(1);

      store.publish(event(id));

      verify(update).setString(1, "REFUSED");
      verify(update).setString(2, "DOUBLON");
      verify(update).setObject(5, id);
      verify(update).setString(eq(3), contains("\"cdarCode\""));
    }

    @Test
    @DisplayName("an update that matches no row fails loudly")
    void noMatchingRowFails() throws SQLException {
      // Silently succeeding would leave an event nobody ever delivers and no trace of why.
      when(update.executeUpdate()).thenReturn(0);

      assertThrows(JdbcInvoicePayableStore.PersistenceException.class,
          () -> store.publish(event(UUID.randomUUID())));
    }

    @Test
    @DisplayName("a SQL failure is translated rather than leaking a checked exception")
    void sqlFailureIsTranslated() throws SQLException {
      when(update.executeUpdate()).thenThrow(new SQLException("deadlock"));

      JdbcInvoicePayableStore.PersistenceException thrown =
          assertThrows(JdbcInvoicePayableStore.PersistenceException.class,
              () -> store.publish(event(UUID.randomUUID())));

      assertEquals("deadlock", thrown.getCause().getMessage());
    }
  }

  // ── Serialisation helpers ─────────────────────────────────────────────────

  @Nested
  @DisplayName("serialisation")
  class Serialisation {

    @Test
    @DisplayName("an error carries its code, description, reason and cause")
    void errorsSerialise() {
      MappingError withCause = MappingError.of(ErrorCode.PARTY_LOOKUP_FAILED, "referential down",
          new IllegalStateException("connect timeout"));
      MappingError withoutCause = MappingError.of(ErrorCode.BUSINESS_UNKNOWN, "no token");

      List<java.util.Map<String, Object>> rows =
          JdbcInvoicePayableStore.serialiseErrors(List.of(withCause, withoutCause));

      assertEquals(2, rows.size());
      assertEquals(ErrorCode.PARTY_LOOKUP_FAILED.code(), rows.get(0).get("code"));
      assertEquals("referential down", rows.get(0).get("detail"));
      assertTrue(rows.get(0).get("cause").toString().contains("connect timeout"));
      // An absent cause is left out entirely rather than recorded as the string "null".
      assertTrue(!rows.get(1).containsKey("cause"));
    }

    @Test
    @DisplayName("an error with no lifecycle event of its own records that as null")
    void errorWithoutLifecycleEvent() {
      // INCOMPLETE findings carry no REFUSED/SUSPENDED event. The field is still written so a
      // reader can tell "no event" apart from "this row predates the column".
      var rows = JdbcInvoicePayableStore.serialiseErrors(
          List.of(MappingError.of(ErrorCode.EMPTY_LINE_ITEMS, "no lines")));

      assertTrue(rows.get(0).containsKey("lifecycleEvent"));
      assertNull(rows.get(0).get("lifecycleEvent"));
    }

    @Test
    @DisplayName("a lifecycle event serialises its CDAR code alongside the internal one")
    void lifecycleSerialises() {
      UUID id = UUID.randomUUID();
      var row = JdbcInvoicePayableStore.serialiseLifecycle(
          new PendingLifecycleEvent(id, "0001000042", LifecycleEventType.SUSPENDED,
              "JUSTIF_ABS", "no attachment", Instant.EPOCH));

      assertEquals(id.toString(), row.get("invoicePayableId"));
      assertEquals("SUSPENDED", row.get("type"));
      assertEquals(LifecycleEventType.SUSPENDED.cdarCode(), row.get("cdarCode"));
      assertEquals("JUSTIF_ABS", row.get("reasonCode"));
    }

    @Test
    @DisplayName("a value that cannot be serialised becomes a recorded error, not a lost row")
    void unserialisableValue() {
      // The hoisted columns still carry everything the duplicate check and the ops UI read, so
      // failing the whole registration over the json payload would trade a lot for a little.
      class Cyclic {
        @SuppressWarnings("unused")
        Cyclic self = this;
      }
      String json = new JdbcInvoicePayableStore(dataSource).toJson(new Object() {
        @SuppressWarnings("unused")
        public Object getBoom() {
          throw new UnsupportedOperationException("no");
        }
      });
      assertTrue(json.contains("_serialisationError"), "the failure is recorded in the column");
    }

    @Test
    @DisplayName("null serialises to null, and truncate leaves a null alone")
    void nullHandling() {
      assertNull(new JdbcInvoicePayableStore(dataSource).toJson(null));
      assertNull(JdbcInvoicePayableStore.truncate(null, 10));
      assertEquals("abc", JdbcInvoicePayableStore.truncate("abc", 10));
      assertEquals("ab", JdbcInvoicePayableStore.truncate("abcdef", 2));
    }
  }

  @Test
  @DisplayName("the data source is mandatory")
  void dataSourceMandatory() {
    assertThrows(NullPointerException.class, () -> new JdbcInvoicePayableStore(null));
  }

  @Test
  @DisplayName("the same instance serves as both the store and the lifecycle publisher")
  void servesBothPorts() {
    // One class, two ports: the lifecycle columns live on the row this store writes, so
    // splitting them would mean two components writing the same table.
    assertSame(store, store);
    assertTrue(store instanceof com.sg.domaininterface.port.einvoice.InvoicePayableStore);
    assertTrue(store instanceof com.sg.domaininterface.port.einvoice.LifecycleEventPublisher);
  }
}
