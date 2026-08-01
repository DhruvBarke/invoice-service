package com.example.invoice.config;

import com.example.invoice.service.registration.port.ExistingInvoicePayableLookup;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * JDBC implementation of {@link ExistingInvoicePayableLookup}.
 *
 * <p>Reads through the {@code ix_ip_provider_ref_status} composite index defined in
 * {@code V2__t_invoice_payable.sql}. LIMIT 1 (or ROWNUM = 1 on Oracle) is unnecessary — the
 * status filter combined with the fact that a REGISTERED row is what the duplicate check
 * cares about makes existence a single-row question in practice.
 */
public final class JdbcExistingInvoicePayableLookup implements ExistingInvoicePayableLookup {

  private static final String SQL = """
      SELECT 1
        FROM t_invoice_payable
       WHERE provider_reference = ?
         AND invoice_status = 'REGISTERED'
      """;

  private final DataSource dataSource;

  public JdbcExistingInvoicePayableLookup(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public boolean existsRegistered(String providerReference) {
    if (providerReference == null || providerReference.isBlank()) return false;
    try (Connection c = dataSource.getConnection();
         PreparedStatement ps = c.prepareStatement(SQL)) {
      ps.setString(1, providerReference);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    } catch (SQLException e) {
      // Duplicate check failing is an infrastructure fault — better to surface as blocking than
      // to silently allow a possible duplicate through. The orchestrator's rule loop will
      // catch this and turn it into a MAPPING_ERROR entry.
      throw new RuntimeException("duplicate check failed for providerReference="
          + providerReference, e);
    }
  }
}
