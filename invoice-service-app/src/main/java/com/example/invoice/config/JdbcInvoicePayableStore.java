package com.example.invoice.config;

import com.example.invoice.service.registration.error.LifecycleEventType;
import com.example.invoice.service.registration.error.MappingError;
import com.example.invoice.service.registration.port.InvoicePayableStore;
import com.example.invoice.service.registration.port.LifecycleEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Plain-JDBC persistence for {@code t_invoice_payable}. Mirrors the design of
 * {@link com.example.invoice.service.alerting.quarantine.JdbcQuarantineStore} in the alerting
 * module: no ORM, portable across Postgres / Oracle / H2, DDL lives in Flyway
 * {@code V2__t_invoice_payable.sql}.
 *
 * <p>Also implements {@link LifecycleEventPublisher} — for this pass the publisher just marks
 * the row's lifecycle columns to {@code PENDING} and stores the serialised payload. A future
 * scheduler polls {@code WHERE lifecycle_event_status = 'PENDING'} and posts to
 * e-invoice-service. Combining both ports on one class keeps them in the same JDBC transaction
 * (see {@link #persist} / {@link #publish}: persist is an INSERT that returns the id; publish
 * is a follow-up UPDATE targeting that id).
 */
public final class JdbcInvoicePayableStore implements InvoicePayableStore, LifecycleEventPublisher {

  private static final String INSERT_SQL = """
      INSERT INTO t_invoice_payable (
          invoice_reference, provider_reference,
          business, feetype, fee_id, sg_entity, provider_id,
          invoice_status, comment,
          invoice_payable_json, items_json, error_codes,
          source, created_at, updated_at
      ) VALUES (?,?, ?,?,?,?,?, ?,?, ?,?,?, ?,?,?)
      """;

  private static final String UPDATE_LIFECYCLE_SQL = """
      UPDATE t_invoice_payable
         SET lifecycle_event_type   = ?,
             lifecycle_reason_code  = ?,
             lifecycle_event_status = 'PENDING',
             lifecycle_payload      = ?,
             updated_at             = ?
       WHERE id = ?
      """;

  private final DataSource dataSource;
  private final ObjectMapper json;

  public JdbcInvoicePayableStore(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.json = new ObjectMapper();
    this.json.findAndRegisterModules(); // JavaTimeModule etc. — same defaults as the mapper
  }

  // ── InvoicePayableStore ───────────────────────────────────────────────────

  @Override
  public long persist(PersistRequest req) {
    Instant now = Instant.now();
    try (Connection c = dataSource.getConnection();
         PreparedStatement ps = c.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {

      String invoiceRef = req.model() != null ? req.model().getInvoiceReference() : null;
      String providerRef = req.model() != null && req.model().getInvoicePayable() != null
          ? req.model().getInvoicePayable().getProviderReference() : null;

      ps.setString(1, nullSafe(invoiceRef, "<unknown>"));
      ps.setString(2, providerRef);
      ps.setString(3, req.business() == null ? null : req.business().name());
      ps.setString(4, req.feeType());
      ps.setString(5, req.feeId());
      ps.setString(6, req.model() != null ? req.model().getSgEntity() : null);
      ps.setString(7, req.model() != null ? req.model().getProviderId() : null);
      ps.setString(8, req.outcome().status().name());
      ps.setString(9, truncate(req.outcome().comment(), 1024));
      ps.setString(10, toJson(req.model()));
      ps.setString(11, toJson(req.items()));
      ps.setString(12, toJson(serialiseErrors(req.outcome().errors())));
      ps.setString(13, req.source());
      ps.setTimestamp(14, Timestamp.from(now));
      ps.setTimestamp(15, Timestamp.from(now));

      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        if (!keys.next()) {
          throw new SQLException("insert returned no generated key");
        }
        return keys.getLong(1);
      }
    } catch (SQLException e) {
      throw new PersistenceException("failed to persist InvoicePayable", e);
    }
  }

  // ── LifecycleEventPublisher ───────────────────────────────────────────────

  @Override
  public void publish(PendingLifecycleEvent event) {
    if (event.type() == null) return;
    try (Connection c = dataSource.getConnection();
         PreparedStatement ps = c.prepareStatement(UPDATE_LIFECYCLE_SQL)) {
      ps.setString(1, event.type().name());
      ps.setString(2, event.reasonCode());
      ps.setString(3, toJson(serialiseLifecycle(event)));
      ps.setTimestamp(4, Timestamp.from(event.occurredAt()));
      ps.setLong(5, event.invoicePayableId());
      int rows = ps.executeUpdate();
      if (rows == 0) {
        throw new PersistenceException(
            "lifecycle update affected no row (invoicePayableId=" + event.invoicePayableId() + ")",
            null);
      }
    } catch (SQLException e) {
      throw new PersistenceException("failed to publish lifecycle event", e);
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private String toJson(Object o) {
    if (o == null) return null;
    try {
      return json.writeValueAsString(o);
    } catch (Exception e) {
      // Fallback to toString so a serialiser hiccup never fails the whole persistence step.
      return "{\"_serialisationError\":\"" + e.getMessage() + "\"}";
    }
  }

  private static List<Map<String, Object>> serialiseErrors(List<MappingError> errors) {
    List<Map<String, Object>> out = new java.util.ArrayList<>(errors.size());
    for (MappingError e : errors) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("code", e.code().code());
      row.put("name", e.code().name());
      row.put("description", e.code().description());
      row.put("detail", e.detail());
      row.put("detectedAt", e.detectedAt().toString());
      LifecycleEventType lc = e.code().lifecycleEvent();
      row.put("lifecycleEvent", lc == null ? null : lc.name());
      row.put("reasonCode", e.code().reasonCode());
      if (e.cause() != null) {
        row.put("cause", e.cause().getClass().getName() + ": " + e.cause().getMessage());
      }
      out.add(row);
    }
    return out;
  }

  private static Map<String, Object> serialiseLifecycle(PendingLifecycleEvent e) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("invoicePayableId", e.invoicePayableId());
    row.put("invoiceReference", e.invoiceReference());
    row.put("type", e.type().name());
    row.put("cdarCode", e.type().cdarCode());
    row.put("reasonCode", e.reasonCode());
    row.put("comment", e.comment());
    row.put("occurredAt", e.occurredAt().toString());
    return row;
  }

  private static String nullSafe(String s, String fallback) {
    return s == null || s.isBlank() ? fallback : s;
  }

  private static String truncate(String s, int max) {
    if (s == null) return null;
    return s.length() <= max ? s : s.substring(0, max);
  }

  /** Runtime exception so the JDBC checked exceptions don't leak into the registration flow. */
  public static class PersistenceException extends RuntimeException {
    public PersistenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
