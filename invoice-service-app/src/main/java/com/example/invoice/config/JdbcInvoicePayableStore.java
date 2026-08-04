package com.example.invoice.config;

import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoiceDocumentPayable;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoiceItem;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoicePayableModel;
import com.example.invoice.service.registration.error.LifecycleEventType;
import com.example.invoice.service.registration.error.MappingError;
import com.example.invoice.service.registration.port.InvoicePayableStore;
import com.example.invoice.service.registration.port.LifecycleEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Plain-JDBC persistence across the three shared invoice-payable tables.
 *
 * <p><b>Three tables, one correlation key.</b> {@code t_invoice_payable} holds the envelope with
 * the {@code InvoicePayable} as a jsonb column; {@code t_invoice_items} holds the lines, keyed
 * back by {@code inv_reference_sg}; {@code t_invoice_document_payable} holds document metadata.
 * They correlate on {@code invoice_reference} and are not foreign-keyed — see
 * {@code V2__invoice_payable.sql} for why.
 *
 * <p><b>These tables are shared.</b> Manual capture and SGAi write the same rows. Every column
 * this class writes that no other producer knows about lives in
 * {@code V3__einvoice_registration_columns.sql} and is nullable, so nothing here constrains what
 * they can store. {@code invoice_flow} records which producer wrote the row.
 *
 * <p><b>Keys are generated in Java, not by the database.</b> The entities use Hibernate's
 * {@code uuid2} generator, so the columns are plain {@code uuid} with no default. Minting them
 * here means no {@code RETURN_GENERATED_KEYS} round-trip and the id is known before the insert
 * runs, which is what lets the items and documents be stamped in the same pass.
 *
 * <p><b>{@code invoice_reference} comes from a sequence.</b> Not from the incoming e-invoice:
 * that id is the supplier's own reference, unique only within the supplier that issued it. It is
 * stored as {@code provider_reference}, which is what the duplicate check keys on. The mapper
 * deliberately leaves {@code InvoicePayableModel.invoiceReference} null for this reason; this
 * class fills it in and writes it back onto the model, its items and its documents.
 *
 * <p>All three inserts run in one transaction. They are not foreign-keyed, but a half-written
 * registration — an envelope with no lines, or lines with no envelope — is a state no reader
 * expects, and it costs nothing to avoid it here.
 *
 * <p>Also implements {@link LifecycleEventPublisher}: publishing marks the envelope row's
 * lifecycle columns {@code PENDING} for the scheduler to drain.
 */
public final class JdbcInvoicePayableStore implements InvoicePayableStore, LifecycleEventPublisher {

  /**
   * Prefix applied to the sequence value to form {@code invoice_reference}.
   *
   * <p>References elsewhere in the system look like {@code "CUS0226368"} — a short alphabetic
   * prefix and a zero-padded number. Whether e-invoicing rows are meant to carry a prefix, and
   * what it should be, is unconfirmed, so this is empty and references come out as bare padded
   * digits. This is the one line to change if a prefix is required.
   */
  static final String REFERENCE_PREFIX = "";

  /** Width of the numeric part. Wide enough that the sequence will not outgrow it. */
  private static final String REFERENCE_FORMAT = "%010d";

  private static final String NEXT_REFERENCE_SQL =
      "SELECT nextval('publicinvoice.seq_invoice_reference')";

  private static final String INSERT_MODEL_SQL = """
      INSERT INTO publicinvoice.t_invoice_payable (
          id, invoice_reference,
          sg_entity, fee_category, provider_id,
          invoice_payable,
          created_date, last_updated_date, created_by_user, last_updated_by_user,
          invoice_date, trading_start_date, trading_end_date,
          ref_cpty_id, invoice_type, invoice_status,
          amount, currency, isdeleted, invoice_flow,
          provider_reference, business, fee_id, fee_type,
          registration_comment, registration_errors
      ) VALUES (
          ?, ?,
          ?, ?, ?,
          ?::jsonb,
          ?, ?, ?, ?,
          ?, ?, ?,
          ?, ?, ?,
          ?, ?, ?, ?,
          ?, ?, ?, ?,
          ?, ?::jsonb
      )
      """;

  private static final String INSERT_ITEM_SQL = """
      INSERT INTO publicinvoice.t_invoice_items (
          invoice_item_id, inv_reference_sg,
          fee_type, grouping_key, nature_of_expense, account_number, product,
          notional_quantity, fee_amount, fee_currency,
          provider_rate, exchanged_rate, exchanged_amount, exchanged_amount_currency,
          vat_amount, vat_amount_currency, debit_credit,
          items_creation_date, items_creation_user,
          items_last_update_date, items_last_update_user,
          item_description, market_region, fee_agreement, business,
          traded_currency, traded_amount, fx_rate
      ) VALUES (
          ?, ?,
          ?, ?, ?, ?, ?,
          ?, ?, ?,
          ?, ?, ?, ?,
          ?, ?, ?,
          ?, ?,
          ?, ?,
          ?, ?, ?, ?,
          ?, ?, ?
      )
      """;

  private static final String INSERT_DOCUMENT_SQL = """
      INSERT INTO publicinvoice.t_invoice_document_payable (
          id, invoice_reference, sg_doc_id,
          document_name, document_type, format,
          incoming_line, sender_address, arrival_time,
          document_reference, document_status,
          registration_status, registration_type,
          subject, body, comment,
          created_by, isdeleted,
          created_date, last_updated_date, last_updated_by_user,
          parser_id, parser_response, parser_source
      ) VALUES (
          ?, ?, ?,
          ?, ?, ?,
          ?, ?, ?,
          ?, ?,
          ?, ?,
          ?, ?, ?,
          ?, ?,
          ?, ?, ?,
          ?, ?, ?
      )
      """;

  private static final String UPDATE_LIFECYCLE_SQL = """
      UPDATE publicinvoice.t_invoice_payable
         SET lifecycle_event_type   = ?,
             lifecycle_reason_code  = ?,
             lifecycle_event_status = 'PENDING',
             lifecycle_payload      = ?::jsonb,
             last_updated_date      = ?
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
  public UUID persist(PersistRequest req) {
    LocalDate today = LocalDate.now();
    LocalDateTime now = LocalDateTime.now();
    UUID id = UUID.randomUUID();

    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        String invoiceReference = nextInvoiceReference(c);
        stampReference(req, id, invoiceReference);

        insertModel(c, req, id, invoiceReference, today);
        insertItems(c, req.items(), invoiceReference, today);
        insertDocuments(c, req.documents(), invoiceReference, now);

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

  /**
   * Write the minted identity back onto the caller's objects.
   *
   * <p>Not cosmetic: the alert, the lifecycle payload and anything else that runs after
   * persistence read these off the model, and quoting a reference the row does not have is
   * worse than quoting none.
   */
  private static void stampReference(PersistRequest req, UUID id, String invoiceReference) {
    if (req.model() != null) {
      req.model().setId(id);
      req.model().setInvoiceReference(invoiceReference);
    }
    for (InvoiceItem item : req.items()) {
      item.setInvReferenceSg(invoiceReference);
    }
    for (InvoiceDocumentPayable doc : req.documents()) {
      doc.setInvoiceReference(invoiceReference);
    }
  }

  private String nextInvoiceReference(Connection c) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(NEXT_REFERENCE_SQL);
         ResultSet rs = ps.executeQuery()) {
      if (!rs.next()) {
        throw new PersistenceException("seq_invoice_reference returned no value", null);
      }
      return REFERENCE_PREFIX + String.format(REFERENCE_FORMAT, rs.getLong(1));
    }
  }

  private void insertModel(Connection c, PersistRequest req, UUID id, String invoiceReference,
                           LocalDate today) throws SQLException {
    InvoicePayableModel m = req.model();

    try (PreparedStatement ps = c.prepareStatement(INSERT_MODEL_SQL)) {
      int i = 1;
      ps.setObject(i++, id);
      ps.setString(i++, invoiceReference);

      ps.setString(i++, m == null ? null : m.getSgEntity());
      ps.setString(i++, m == null ? null : m.getFeeCategory());
      ps.setString(i++, m == null ? null : m.getProviderId());

      // NOT NULL on the column: an absent payable stores an empty object rather than
      // failing the whole registration, because the row is still the record that this
      // invoice arrived and could not be mapped.
      ps.setString(i++, nullSafeJson(m == null ? null : m.getInvoicePayable()));

      setDate(ps, i++, today);
      setDate(ps, i++, today);
      ps.setString(i++, m == null ? null : m.getCreatedByUser());
      ps.setString(i++, m == null ? null : m.getLastUpdatedByUser());

      setDate(ps, i++, m == null ? null : m.getInvoiceDate());
      setDate(ps, i++, m == null ? null : m.getTradingStartDate());
      setDate(ps, i++, m == null ? null : m.getTradingEndDate());

      ps.setString(i++, m == null ? null : m.getRefCptyId());
      ps.setString(i++, m == null ? null : m.getInvoiceType());
      ps.setString(i++, req.outcome().status().name());

      setDecimal(ps, i++, m == null ? null : m.getAmount());
      ps.setString(i++, m == null ? null : m.getCurrency());
      ps.setBoolean(i++, m != null && m.isDeleted());
      ps.setString(i++, req.invoiceFlow());

      ps.setString(i++, providerReferenceOf(m));
      ps.setString(i++, req.business() == null ? null : req.business().name());
      ps.setString(i++, req.feeId());
      ps.setString(i++, req.feeType());

      ps.setString(i++, req.outcome().comment());
      ps.setString(i, toJson(serialiseErrors(req.outcome().errors())));

      ps.executeUpdate();
    }
  }

  private static String providerReferenceOf(InvoicePayableModel m) {
    return m == null || m.getInvoicePayable() == null
        ? null
        : m.getInvoicePayable().getProviderReference();
  }

  private void insertItems(Connection c, List<InvoiceItem> items, String invoiceReference,
                           LocalDate today) throws SQLException {
    if (items.isEmpty()) {
      return;
    }
    try (PreparedStatement ps = c.prepareStatement(INSERT_ITEM_SQL)) {
      for (InvoiceItem item : items) {
        int i = 1;
        ps.setObject(i++, item.getInvoiceItemId() == null ? UUID.randomUUID()
            : item.getInvoiceItemId());
        ps.setString(i++, invoiceReference);

        ps.setString(i++, item.getFeeType());
        ps.setString(i++, item.getGroupingKey());
        ps.setString(i++, item.getNatureOfExpense());
        ps.setString(i++, item.getAccountNumber());
        ps.setString(i++, item.getProduct());

        setDecimal(ps, i++, item.getNotionQuantity());
        setDecimal(ps, i++, item.getFeeAmount());
        ps.setString(i++, item.getFeeCurrency());

        setDecimal(ps, i++, item.getProviderRate());
        setDecimal(ps, i++, item.getExchangedRate());
        setDecimal(ps, i++, item.getExchangedAmount());
        ps.setString(i++, item.getExchangedAmountCurrency());

        setDecimal(ps, i++, item.getVatAmount());
        ps.setString(i++, item.getVatAmountCurrency());
        ps.setString(i++, item.getDebitCredit());

        setDate(ps, i++, item.getItemsCreationDate() == null ? today
            : item.getItemsCreationDate());
        ps.setString(i++, item.getItemsCreationUser());
        setDate(ps, i++, item.getItemsLastUpdateDate() == null ? today
            : item.getItemsLastUpdateDate());
        ps.setString(i++, item.getItemsLastUpdateUser());

        ps.setString(i++, item.getItemDescription());
        ps.setString(i++, item.getMarketRegion());
        ps.setString(i++, item.getFeeAgreement());
        ps.setString(i++, item.getBusiness());

        ps.setString(i++, item.getTradedCurrency());
        ps.setString(i++, item.getTradedAmount());
        ps.setString(i, item.getFxRate());

        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private void insertDocuments(Connection c, List<InvoiceDocumentPayable> documents,
                               String invoiceReference, LocalDateTime now) throws SQLException {
    if (documents.isEmpty()) {
      return;
    }
    try (PreparedStatement ps = c.prepareStatement(INSERT_DOCUMENT_SQL)) {
      for (InvoiceDocumentPayable d : documents) {
        int i = 1;
        ps.setObject(i++, d.getId() == null ? UUID.randomUUID() : d.getId());
        ps.setString(i++, invoiceReference);
        // Null until SGDoc has the content. The row still records that the document arrived.
        ps.setString(i++, d.getSgDocId());

        ps.setString(i++, truncate(d.getDocumentName(), 512));
        ps.setString(i++, d.getDocumentType());
        ps.setString(i++, truncate(d.getFormat(), 128));

        ps.setString(i++, d.getIncomingLine());
        ps.setString(i++, truncate(d.getSenderAddress(), 256));
        ps.setString(i++, d.getArrivalTime());

        ps.setString(i++, truncate(d.getDocumentReference(), 128));
        ps.setString(i++, d.getDocumentStatus());

        setBoolean(ps, i++, d.getRegistrationStatus());
        ps.setString(i++, d.getRegistrationType());

        ps.setString(i++, d.getSubject());
        ps.setString(i++, d.getBody());
        ps.setString(i++, d.getComment());

        ps.setString(i++, d.getActionPerformedBy());
        ps.setBoolean(i++, d.isDeleted());

        setTimestamp(ps, i++, d.getCreatedDate() == null ? now : d.getCreatedDate());
        setTimestamp(ps, i++, d.getLastUpdatedDate() == null ? now : d.getLastUpdatedDate());
        ps.setString(i++, d.getLastUpdatedByUser());

        ps.setString(i++, d.getParserId());
        ps.setString(i++, d.getParserResponse());
        ps.setString(i, d.getParserSource());

        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  // ── LifecycleEventPublisher ───────────────────────────────────────────────

  @Override
  public void publish(PendingLifecycleEvent event) {
    try (Connection c = dataSource.getConnection();
         PreparedStatement ps = c.prepareStatement(UPDATE_LIFECYCLE_SQL)) {
      ps.setString(1, event.type().name());
      ps.setString(2, event.reasonCode());
      ps.setString(3, toJson(serialiseLifecycle(event)));
      setDate(ps, 4, LocalDate.now());
      ps.setObject(5, event.invoicePayableId());
      if (ps.executeUpdate() == 0) {
        throw new PersistenceException(
            "lifecycle update matched no row (id=" + event.invoicePayableId() + ")", null);
      }
    } catch (SQLException e) {
      throw new PersistenceException("failed to publish the lifecycle event", e);
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private static void setDecimal(PreparedStatement ps, int index, BigDecimal value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.DECIMAL);
    } else {
      ps.setBigDecimal(index, value);
    }
  }

  private static void setDate(PreparedStatement ps, int index, LocalDate value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.DATE);
    } else {
      ps.setObject(index, value);
    }
  }

  private static void setTimestamp(PreparedStatement ps, int index, LocalDateTime value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.TIMESTAMP);
    } else {
      ps.setTimestamp(index, Timestamp.valueOf(value));
    }
  }

  private static void setBoolean(PreparedStatement ps, int index, Boolean value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.BOOLEAN);
    } else {
      ps.setBoolean(index, value);
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
    row.put("invoicePayableId", e.invoicePayableId().toString());
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
