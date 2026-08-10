package com.sg.jpa.adapter;

import com.sg.domaininterface.model.provider.ProviderSetup;
import com.sg.domaininterface.port.out.ProviderSetupLookup;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * JDBC implementation of {@link ProviderSetupLookup}.
 *
 * <p><b>The table and column names here are unverified.</b> The manual registration path reads
 * this through {@code invoicePayableRepository.getProviderSetupDetailsByMnemonic(providerMnemo,
 * feeCategory, entityMnemonic)}, which tells us the three keys and the two answers but not what
 * they are called in the schema — no entity for this table has been supplied. The names below
 * follow the conventions of the tables that have (snake_case, {@code publicinvoice}, an
 * {@code isdeleted} flag), and this is the one class to correct once the real DDL is to hand.
 *
 * <p>Getting them wrong fails loudly rather than quietly: an unknown relation or column raises at
 * the first lookup, and the enricher records it as an alert-only failure with both flags left
 * unset. It cannot silently return "not activated", which would look like a decision.
 *
 * <p><b>Soft-deleted rows do not count.</b> Deactivating a provider by soft-deleting its setup row
 * would otherwise go on activating them for payment indefinitely.
 */
public final class JdbcProviderSetupLookup implements ProviderSetupLookup {

  private static final String SQL = """
      SELECT payment_activation, accounting_activation
        FROM publicinvoice.t_provider_setup
       WHERE provider_mnemo = ?
         AND fee_category = ?
         AND sg_entity_mnemonic = ?
         AND isdeleted = false
      """;

  private final DataSource dataSource;

  public JdbcProviderSetupLookup(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  /**
   * @return the setup row, or empty when any key is absent or no row matches. A missing key
   *         cannot select a row, and querying on nulls would return nothing anyway — with a
   *         round-trip to find that out.
   */
  @Override
  public Optional<ProviderSetup> find(String providerMnemo, String feeCategory,
                                      String sgEntityMnemonic) {
    if (isBlank(providerMnemo) || isBlank(feeCategory) || isBlank(sgEntityMnemonic)) {
      return Optional.empty();
    }
    try (Connection c = dataSource.getConnection();
         PreparedStatement ps = c.prepareStatement(SQL)) {
      ps.setString(1, providerMnemo);
      ps.setString(2, feeCategory);
      ps.setString(3, sgEntityMnemonic);
      try (ResultSet rs = ps.executeQuery()) {
        // One exit, matching JdbcExistingInvoicePayableLookup. Two returns out of a
        // try-with-resources give the compiler two paths to unwind the result set on, and the
        // synthetic branch that guards the second is not reachable from any test.
        //
        // getBoolean reads a SQL NULL as false, which is the right reading here: a row that does
        // not say a provider is activated has not activated them.
        return rs.next()
            ? Optional.of(new ProviderSetup(
                rs.getBoolean("payment_activation"), rs.getBoolean("accounting_activation")))
            : Optional.empty();
      }
    } catch (SQLException e) {
      throw new ProviderSetupLookupException(
          "provider setup lookup failed for mnemonic=" + providerMnemo
              + ", feeCategory=" + feeCategory + ", entity=" + sgEntityMnemonic, e);
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /**
   * Dedicated type rather than a bare {@code RuntimeException}, so "the setup table is wrong or
   * missing" is distinguishable from anything else the enricher might catch.
   */
  public static class ProviderSetupLookupException extends RuntimeException {
    /** Pinned so a rolling deployment cannot make an in-flight instance unreadable. */
    private static final long serialVersionUID = 1L;

    public ProviderSetupLookupException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
