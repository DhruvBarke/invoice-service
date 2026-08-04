package com.sg.jpa.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two read-only adapters: the duplicate check and the fee-type referential.
 *
 * <p>Mocked JDBC, with the same caveat as {@link JdbcInvoicePayableStoreTest} — this proves the
 * query issued and the parameters bound, not that the SQL parses against the real schema.
 */
class JdbcLookupAndReferentialTest {

  private record Fixture(DataSource dataSource, PreparedStatement statement, ResultSet rows) {}

  private static Fixture fixture() throws SQLException {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);

    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    return new Fixture(dataSource, statement, rows);
  }

  // ── Duplicate check ───────────────────────────────────────────────────────

  @Nested
  @DisplayName("the duplicate check")
  class Duplicates {

    @Test
    @DisplayName("a matching row means the supplier's invoice is already registered")
    void matchIsADuplicate() throws SQLException {
      Fixture f = fixture();
      when(f.rows().next()).thenReturn(true);

      assertTrue(new JdbcExistingInvoicePayableLookup(f.dataSource())
          .existsRegistered("SUP-INV-1"));
      verify(f.statement()).setString(1, "SUP-INV-1");
    }

    @Test
    @DisplayName("no matching row means it is new")
    void noMatchIsNew() throws SQLException {
      Fixture f = fixture();
      when(f.rows().next()).thenReturn(false);

      assertFalse(new JdbcExistingInvoicePayableLookup(f.dataSource())
          .existsRegistered("SUP-INV-1"));
    }

    @Test
    @DisplayName("a null or blank reference is answered without touching the database")
    void absentReferenceShortCircuits() throws SQLException {
      Fixture f = fixture();
      JdbcExistingInvoicePayableLookup lookup =
          new JdbcExistingInvoicePayableLookup(f.dataSource());

      // A blank reference cannot match anything, and a query for one would be a round trip that
      // could only ever come back empty.
      assertFalse(lookup.existsRegistered(null));
      assertFalse(lookup.existsRegistered(""));
      assertFalse(lookup.existsRegistered("   "));

      verify(f.dataSource(), never()).getConnection();
    }

    @Test
    @DisplayName("a failed check is raised, never quietly answered false")
    void failureIsRaised() throws SQLException {
      Fixture f = fixture();
      when(f.statement().executeQuery()).thenThrow(new SQLException("connection reset"));

      // Answering false would let a duplicate through on an infrastructure fault, which is the
      // one outcome this check exists to prevent.
      JdbcExistingInvoicePayableLookup.DuplicateCheckException thrown =
          assertThrows(JdbcExistingInvoicePayableLookup.DuplicateCheckException.class,
              () -> new JdbcExistingInvoicePayableLookup(f.dataSource())
                  .existsRegistered("SUP-INV-1"));

      assertTrue(thrown.getMessage().contains("SUP-INV-1"),
          "the message names the invoice, so the alert points at something specific");
      assertEquals("connection reset", thrown.getCause().getMessage());
    }

    @Test
    @DisplayName("the data source is mandatory")
    void dataSourceMandatory() {
      assertThrows(NullPointerException.class,
          () -> new JdbcExistingInvoicePayableLookup(null));
    }
  }

  // ── Fee-type referential ──────────────────────────────────────────────────

  @Nested
  @DisplayName("the fee-type referential")
  class FeeTypes {

    @Test
    @DisplayName("rows load in order, as an id-to-type map")
    void rowsLoad() throws SQLException {
      Fixture f = fixture();
      when(f.rows().next()).thenReturn(true, true, false);
      when(f.rows().getString(1)).thenReturn("F01", "F02");
      when(f.rows().getString(2)).thenReturn("CUSTODY", "BROKERAGE_PRINCIPAL");

      Map<String, String> loaded =
          new JdbcFeeTypeRepository(f.dataSource()).findAllFeeTypes();

      assertEquals(2, loaded.size());
      assertEquals("CUSTODY", loaded.get("F01"));
      assertEquals("BROKERAGE_PRINCIPAL", loaded.get("F02"));
      assertEquals(List.of("F01", "F02"), List.copyOf(loaded.keySet()),
          "insertion order is kept, so the matcher's tie-breaking is reproducible");
    }

    @Test
    @DisplayName("an empty referential is an empty map, not a failure")
    void emptyReferential() throws SQLException {
      Fixture f = fixture();
      when(f.rows().next()).thenReturn(false);

      assertTrue(new JdbcFeeTypeRepository(f.dataSource()).findAllFeeTypes().isEmpty());
    }

    @Test
    @DisplayName("a load failure is raised rather than served as an empty referential")
    void loadFailureIsRaised() throws SQLException {
      Fixture f = fixture();
      when(f.statement().executeQuery()).thenThrow(new SQLException("table missing"));

      // An empty map would make every fee type unresolvable and quietly refuse every invoice.
      IllegalStateException thrown = assertThrows(IllegalStateException.class,
          () -> new JdbcFeeTypeRepository(f.dataSource()).findAllFeeTypes());

      assertTrue(thrown.getMessage().contains("fee-type referential"));
    }

    @Test
    @DisplayName("the data source is mandatory")
    void dataSourceMandatory() {
      assertThrows(NullPointerException.class, () -> new JdbcFeeTypeRepository(null));
    }
  }
}
