package com.sg.jpa.adapter;

import com.sg.domaininterface.port.einvoice.ExistingInvoicePayableLookup;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * JDBC implementation of {@link ExistingInvoicePayableLookup}.
 *
 * <p>Reads through the {@code ix_ip_provider_ref_active} partial index defined in
 * {@code V3__einvoice_registration_columns.sql}.
 *
 * <p>The key is {@code provider_reference} — the incoming e-invoice's own id — and NOT
 * {@code invoice_reference}, which this service mints fresh from a sequence for every
 * registration and so could never collide. "Have we already registered this supplier's
 * invoice?" is only answerable against the supplier's own reference.
 *
 * <p><b>Soft-deleted rows do not count.</b> Retracting a bad registration is a soft delete, and
 * the point of retracting one is to let a corrected invoice be sent again. Without the
 * {@code isdeleted} filter the retraction would be inert: the row would go on rejecting every
 * resubmission as a duplicate, with nothing short of a hard delete able to clear it.
 *
 * <p>No LIMIT 1: the status filter makes existence a single-row question in practice, and
 * {@code rs.next()} stops reading after the first row regardless.
 */
public final class JdbcExistingInvoicePayableLookup implements ExistingInvoicePayableLookup {

  private static final String SQL = """
      SELECT 1
        FROM publicinvoice.t_invoice_payable
       WHERE provider_reference = ?
         AND invoice_status = 'REGISTERED'
         AND isdeleted = false
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
      throw new DuplicateCheckException(
          "duplicate check failed for providerReference=" + providerReference, e);
    }
  }

  /**
   * Dedicated type rather than a bare {@code RuntimeException}, so a caller that wants to
   * distinguish "the duplicate check itself broke" from "the mapper broke" can catch this
   * specifically.
   */
  public static class DuplicateCheckException extends RuntimeException {
    public DuplicateCheckException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
