package com.example.invoice.config;

import com.example.invoice.mapper.einvoice.MultipartExtractionService.ExtractedAttachment;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoiceItem;
import com.example.invoice.service.registration.error.LifecycleEventType;
import com.example.invoice.service.registration.error.MappingError;
import com.example.invoice.service.registration.port.InvoicePayableStore;
import com.example.invoice.service.registration.port.LifecycleEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Plain-JDBC persistence across the three invoice-payable tables.
 *
 * <p><b>Three tables, one correlation key.</b> {@code t_invoice_payable} holds the envelope with
 * the {@code InvoicePayable} as a JSON column; {@code t_invoice_item} holds the lines;
 * {@code t_invoice_documents} holds the attachments. They correlate on
 * {@code invoice_reference} and are not joined by foreign keys — see
 * {@code V2__invoice_payable.sql} for why.
 *
 * <p><b>The invoice reference is minted here.</b> {@code seq_invoice_reference} supplies it, not
 * the incoming e-invoice: the e-invoice's own id is unique only within the supplier that issued
 * it, and is stored as {@code provider_reference} instead. The mapper deliberately leaves
 * {@code InvoicePayableModel.invoiceReference} null for exactly this reason, so this class fills
 * it in and hands the value back on the returned {@link PersistedInvoice}.
 *
 * <p>All three inserts run in one transaction. They are not FK-joined, but a half-written
 * registration — an envelope with no lines, or lines with no envelope — is a state no reader
 * expects, and it costs nothing to avoid it here.
 *
 * <p>Also implements {@link LifecycleEventPublisher}: publishing marks the envelope row's
 * lifecycle columns {@code PENDING} for the scheduler to drain.
 */
public final class JdbcInvoicePayableStore implements InvoicePayableStore, LifecycleEventPublisher {

  private static final String NEXT_REFERENCE_SQL =
      "SELECT NEXT VALUE FOR seq_invoice_reference";

  private static final String INSERT_MODEL_SQL = """
      INSERT INTO t_invoice_payable (
          invoice_reference, provider_reference,
          business, feetype, fee_id, sg_entity, provider_id,
          invoice_status, comment,
          invoice_payable_json, error_codes,
          source, created_at, updated_at
      ) VALUES (?,?, ?,?,?,?,?, ?,?, ?,?, ?,?,?)
      """;

  private static final String INSERT_ITEM_SQL = """
      INSERT INTO t_invoice_item (
          invoice_reference, invoice_item_id, description,
          fee_type, fee_amount, fee_currency, notion_quantity,
          grouping_key, nature_of_expense, created_at, updated_at
      ) VALUES (?,?,?, ?,?,?,?, ?,?,?,?)
      """;

  private static final String INSERT_DOCUMENT_SQL = """
      INSERT INTO t_invoice_documents (
          invoice_reference, filename, mime_type, document_type,
          source_channel, size_bytes, content, created_at
      ) VALUES (?,?,?,?, ?,?,?,?)
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
    this.json.findAndRegisterModules();
  }

  // ── InvoicePayableStore ───────────────────────────────────────────────────

  @Override
  public long persist(PersistRequest req) {
    Instant now = Instant.now();
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        String invoiceReference = nextInvoiceReference(c);
        // Hand the minted reference back to the caller's model so anything downstream of
        // persistence — the alert, the lifecycle payload — quotes the same value the row has.
        if (req.model() != null) {
          req.model().setInvoiceReference(invoiceReference);
        }

        long id = insertModel(c, req, invoiceReference, now);
        insertItems(c, req.items(), invoiceReference, now);
        insertDocuments(c, req, invoiceReference, now);

        c.commit();
        return id;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw new PersistenceException("failed to persist the invoice payable", e);
    }
  }

  private String nextInvoiceReference(Connection c) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(NEXT_REFERENCE_SQL);
         ResultSet rs = ps.executeQuery()) {
      if (!rs.next()) {
        throw new PersistenceException("seq_invoice_reference returned no value", null);
      }
      return String.valueOf(rs.getLong(1));
    }
  }

  private long insertModel(Connection c, PersistRequest req, String invoiceReference, Instant now)
      throws SQLException {
    try (PreparedStatement ps =
             c.prepareStatement(INSERT_MODEL_SQL, Statement.RETURN_GENERATED_KEYS)) {

      String providerReference = req.model() != null && req.model().getInvoicePayable() != null
          ? req.model().getInvoicePayable().getProviderReference() : null;

      ps.setString(1, invoiceReference);
      ps.setString(2, providerReference);
      ps.setString(3, req.business() == null ? null : req.business().name());
      ps.setString(4, req.feeType());
      ps.setString(5, req.feeId());
      ps.setString(6, req.model() == null ? null : req.model().getSgEntity());
      ps.setString(7, req.model() == null ? null : req.model().getProviderId());
      ps.setString(8, req.outcome().status().name());
      ps.setString(9, truncate(req.outcome().comment(), 1024));
      ps.setString(10, nullSafeJson(req.model() == null ? null : req.model().getInvoicePayable()));
      ps.setString(11, toJson(serialiseErrors(req.outcome().errors())));
      ps.setString(12, req.source());
      ps.setTimestamp(13, Timestamp.from(now));
      ps.setTimestamp(14, Timestamp.from(now));

      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        if (!keys.next()) {
          throw new PersistenceException("insert into t_invoice_payable returned no key", null);
        }
        return keys.getLong(1);
      }
    }
  }

  private void insertItems(Connection c, List<InvoiceItem> items, String invoiceReference,
                           Instant now) throws SQLException {
    if (items.isEmpty()) {
      return;
    }
    try (PreparedStatement ps = c.prepareStatement(INSERT_ITEM_SQL)) {
      for (InvoiceItem item : items) {
        ps.setString(1, invoiceReference);
        ps.setString(2, item.getInvoiceItemId() == null ? null : item.getInvoiceItemId().toString());
        ps.setString(3, truncate(item.getItemDescription(), 512));
        ps.setString(4, item.getFeeType());
        setBigDecimal(ps, 5, item.getFeeAmount());
        ps.setString(6, item.getFeeCurrency());
        setBigDecimal(ps, 7, item.getNotionQuantity());
        ps.setString(8, item.getGroupingKey());
        ps.setString(9, item.getNatureOfExpense());
        ps.setTimestamp(10, Timestamp.from(now));
        ps.setTimestamp(11, Timestamp.from(now));
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private void insertDocuments(Connection c, PersistRequest req, String invoiceReference,
                               Instant now) throws SQLException {
    List<DocumentRow> rows = new ArrayList<>();
    for (ExtractedAttachment a : req.jsonAttachments()) {
      rows.add(new DocumentRow(a, "EINVOICE_BODY"));
    }
    for (ExtractedAttachment a : req.multipartAttachments()) {
      rows.add(new DocumentRow(a, "MULTIPART"));
    }
    if (rows.isEmpty()) {
      return;
    }

    try (PreparedStatement ps = c.prepareStatement(INSERT_DOCUMENT_SQL)) {
      for (DocumentRow row : rows) {
        ExtractedAttachment a = row.attachment();
        ps.setString(1, invoiceReference);
        ps.setString(2, truncate(a.filename(), 256));
        ps.setString(3, a.mimeType());
        ps.setString(4, documentTypeOf(a.filename()));
        ps.setString(5, row.channel());
        if (a.bytes() == null) {
          ps.setNull(6, Types.BIGINT);
          ps.setNull(7, Types.BLOB);
        } else {
          ps.setLong(6, a.bytes().length);
          ps.setBytes(7, a.bytes());
        }
        ps.setTimestamp(8, Timestamp.from(now));
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  /** Which attachment this is, so the trade-file rule's verdict is legible after the fact. */
  static String documentTypeOf(String filename) {
    if (filename == null) {
      return "OTHER";
    }
    String lower = filename.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".pdf")) {
      return "PDF";
    }
    if (lower.endsWith(".csv") || lower.endsWith(".xlsx")) {
      return "TRADE_FILE";
    }
    return "OTHER";
  }

  private record DocumentRow(ExtractedAttachment attachment, String channel) {}

  // ── LifecycleEventPublisher ───────────────────────────────────────────────

  @Override
  public void publish(PendingLifecycleEvent event) {
    if (event.type() == null) {
      return;
    }
    try (Connection c = dataSource.getConnection();
         PreparedStatement ps = c.prepareStatement(UPDATE_LIFECYCLE_SQL)) {
      ps.setString(1, event.type().name());
      ps.setString(2, event.reasonCode());
      ps.setString(3, toJson(serialiseLifecycle(event)));
      ps.setTimestamp(4, Timestamp.from(event.occurredAt()));
      ps.setLong(5, event.invoicePayableId());
      if (ps.executeUpdate() == 0) {
        throw new PersistenceException(
            "lifecycle update matched no row (id=" + event.invoicePayableId() + ")", null);
      }
    } catch (SQLException e) {
      throw new PersistenceException("failed to publish the lifecycle event", e);
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private static void setBigDecimal(PreparedStatement ps, int index, java.math.BigDecimal value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.DECIMAL);
    } else {
      ps.setBigDecimal(index, value);
    }
  }

  /** The payload column is NOT NULL, so an absent payable stores an empty object. */
  private String nullSafeJson(Object o) {
    String serialised = toJson(o);
    return serialised == null ? "{}" : serialised;
  }

  String toJson(Object o) {
    if (o == null) {
      return null;
    }
    try {
      return json.writeValueAsString(o);
    } catch (JsonProcessingException e) {
      // A serialiser hiccup must not fail the whole registration — the hoisted columns still
      // carry everything the duplicate check and the ops UI read.
      return "{\"_serialisationError\":\"" + e.getOriginalMessage() + "\"}";
    }
  }

  static List<Map<String, Object>> serialiseErrors(List<MappingError> errors) {
    List<Map<String, Object>> out = new ArrayList<>(errors.size());
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

  static Map<String, Object> serialiseLifecycle(PendingLifecycleEvent e) {
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

  static String truncate(String s, int max) {
    if (s == null) {
      return null;
    }
    return s.length() <= max ? s : s.substring(0, max);
  }

  /** Runtime type so JDBC's checked exceptions do not leak into the registration flow. */
  public static class PersistenceException extends RuntimeException {
    public PersistenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
