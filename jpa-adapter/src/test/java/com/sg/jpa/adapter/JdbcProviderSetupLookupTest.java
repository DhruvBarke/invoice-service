package com.sg.jpa.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sg.domaininterface.model.provider.ProviderSetup;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading a provider's activation flags.
 *
 * <p>Mocked JDBC, with the same caveat as the other adapters here: this proves the query issued
 * and the parameters bound, not that the SQL parses. That caveat bites harder for this class than
 * for its neighbours — the table and column names are conventions rather than transcriptions, as
 * no entity for the setup table has been supplied.
 */
class JdbcProviderSetupLookupTest {

  private record Fixture(DataSource dataSource, PreparedStatement statement, ResultSet rows) {}

  private static Fixture fixture() throws SQLException {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);

    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    return new Fixture(dataSource, statement, rows);
  }

  @Test
  @DisplayName("the three keys are bound in order and both flags come back")
  void readsBothFlags() throws SQLException {
    Fixture f = fixture();
    when(f.rows().next()).thenReturn(true);
    when(f.rows().getBoolean("payment_activation")).thenReturn(true);
    when(f.rows().getBoolean("accounting_activation")).thenReturn(false);

    Optional<ProviderSetup> setup =
        new JdbcProviderSetupLookup(f.dataSource()).find("ACME", "CUSTODY", "SGPAR");

    assertTrue(setup.isPresent());
    assertTrue(setup.get().paymentActivation());
    assertFalse(setup.get().accountingActivation());

    // Bound positionally: swapping two would silently read another provider's row.
    verify(f.statement()).setString(1, "ACME");
    verify(f.statement()).setString(2, "CUSTODY");
    verify(f.statement()).setString(3, "SGPAR");
  }

  @Test
  @DisplayName("no row is empty, which is a normal answer")
  void noRowIsEmpty() throws SQLException {
    // A provider not yet onboarded for a fee category simply has no row. That is not a failure,
    // and the caller reads it as "not activated".
    Fixture f = fixture();
    when(f.rows().next()).thenReturn(false);

    assertTrue(new JdbcProviderSetupLookup(f.dataSource())
        .find("ACME", "CUSTODY", "SGPAR").isEmpty());
  }

  @Test
  @DisplayName("a missing key short-circuits without a round trip")
  void missingKeysSkipTheQuery() throws SQLException {
    Fixture f = fixture();
    JdbcProviderSetupLookup lookup = new JdbcProviderSetupLookup(f.dataSource());

    // Querying on a null cannot select a row, so the round trip only buys a slower empty.
    assertTrue(lookup.find(null, "CUSTODY", "SGPAR").isEmpty());
    assertTrue(lookup.find("ACME", null, "SGPAR").isEmpty());
    assertTrue(lookup.find("ACME", "CUSTODY", null).isEmpty());
    assertTrue(lookup.find("  ", "CUSTODY", "SGPAR").isEmpty());
    assertTrue(lookup.find("ACME", "  ", "SGPAR").isEmpty());
    assertTrue(lookup.find("ACME", "CUSTODY", "  ").isEmpty());

    verify(f.dataSource(), never()).getConnection();
  }

  @Test
  @DisplayName("a SQL failure is raised, never served as \"not activated\"")
  void sqlFailureIsRaised() throws SQLException {
    // The names in this query are unverified, so an unknown relation is a realistic first
    // failure. Returning empty would look exactly like a provider nobody had onboarded, and the
    // flags would read as a decision rather than as a lookup that never happened.
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenThrow(new SQLException("relation does not exist"));

    JdbcProviderSetupLookup.ProviderSetupLookupException thrown = assertThrows(
        JdbcProviderSetupLookup.ProviderSetupLookupException.class,
        () -> new JdbcProviderSetupLookup(dataSource).find("ACME", "CUSTODY", "SGPAR"));

    assertTrue(thrown.getMessage().contains("ACME"),
        "an alert nobody can trace to a provider is one someone has to reproduce");
    assertEquals("relation does not exist", thrown.getCause().getMessage());
  }

  @Test
  @DisplayName("a failure part-way through reading is raised, and the result set still closes")
  void failureWhileReadingIsRaised() throws SQLException {
    // Distinct from the connection failing: here the statement ran and the read broke, so the
    // resources are unwound with an exception already in flight. A leak on that path shows up as
    // connection-pool exhaustion under load rather than as anything resembling this query.
    Fixture f = fixture();
    when(f.rows().next()).thenThrow(new SQLException("connection reset by peer"));

    assertThrows(JdbcProviderSetupLookup.ProviderSetupLookupException.class,
        () -> new JdbcProviderSetupLookup(f.dataSource()).find("ACME", "CUSTODY", "SGPAR"));

    verify(f.rows()).close();
    verify(f.statement()).close();
  }

  @Test
  @DisplayName("the data source is mandatory")
  void dataSourceIsMandatory() {
    assertThrows(NullPointerException.class, () -> new JdbcProviderSetupLookup(null));
  }
}
