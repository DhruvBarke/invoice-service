package com.sg.jpa.entity;

import com.sg.domaininterface.model.payableinvoice.InvoicePayable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One {@code publicinvoice.t_invoice_payable} row.
 *
 * <p><b>Field-for-field with the JPA entity in the target repo, minus the annotations.</b> The
 * adapters here use plain JDBC, so nothing needs {@code @Entity} to work; the class exists in
 * this shape so migrating it is adding annotations rather than reconstructing a mapping. Keep
 * the field names aligned with the target entity — that alignment is the entire point, and a
 * rename here silently costs whoever does the migration an afternoon.
 *
 * <p><b>This is the row, not the model.</b> {@link InvoicePayable} is the nested payload that
 * travels as jsonb in {@link #invoicePayable}; the columns beside it are the ones something
 * actually queries — {@code providerReference} for the duplicate check, {@code business} and
 * {@code invoiceStatus} for the ops UI, the lifecycle columns for the scheduler drain. The rest
 * of the payload stays in the json because none of it is filtered on and it gains a field most
 * quarters.
 *
 * <p>The {@code isDeleted} flag is load-bearing: soft-deleting a bad registration is how an
 * operator lets a corrected invoice through, so every read that decides whether an invoice
 * already exists has to filter on it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoicePayableEntity {

  private UUID id;
  private String invoiceReference;
  private String sgEntity;
  private String feeCategory;
  private String providerId;

  /** The jsonb payload. */
  private InvoicePayable invoicePayable;

  private LocalDate createdDate;
  private LocalDate lastUpdatedDate;
  private String createdByUser;
  private String lastUpdatedByUser;

  /** String in the target entity despite being a date. Mirrored, not corrected. */
  private String reAttachmentDate;

  private LocalDate invoiceDate;
  private LocalDate tradingStartDate;
  private LocalDate tradingEndDate;

  private String refCptyId;
  private String invoiceType;
  private String invoiceStatus;
  private String assignedTo;
  private String ssiStatus;
  private String priority;

  private BigDecimal amount;
  private String currency;

  private boolean isDeleted;
  private String assetClass;

  /** MANUAL / SGAI / EINVOICE — which producer wrote the row. */
  private String invoiceFlow;

  private String reconProcess;

  // ── e-invoicing additions (V3), null for every other producer ──────────────

  /** The supplier's own id for this invoice. The duplicate check keys on it. */
  private String providerReference;

  private String business;
  private String feeId;
  private String feeType;
  private String registrationComment;

  /** Every MappingError from the registration, as json. */
  private String registrationErrors;

  private String lifecycleEventType;
  private String lifecycleReasonCode;
  private String lifecycleEventStatus;
  private String lifecyclePayload;
}
