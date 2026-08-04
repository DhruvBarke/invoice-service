package com.example.invoice.service.domain.model.report;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;
import lombok.*;

/**
 * Top-level wrapper for a Flux 10 transmission, mirroring the
 * {@link com.sg.domaininterface.model.payableinvoice.InvoicePayableModel}
 * pattern: a small envelope of invoice-service metadata plus the actual
 * payload ({@link Report}).
 *
 * <p>Callers persist this in the invoice-service DB alongside the
 * {@code InvoicePayableModel} it was built from; the {@code Report}
 * payload is what gets XML-serialised and submitted to the PA.
 *
 * <p>The metadata fields here mirror the on-disk persistence columns used
 * by the existing reporting persistence layer (created/updated tracking,
 * status enum, link to the source invoice-payable). Field shapes follow the
 * conventions used by {@code InvoicePayableModel}: {@link LocalDate} with
 * the invoice-service's standard {@code LocalDateDeserializer} /
 * {@code LocalDateSerializer}, never the Flux 10 compact format (the
 * compact format is only used for the wire-bound {@link Report} payload).
 */
@Data
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
public class ReportModel implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Internal id (UUID). */
  private UUID id;

  /** SIREN of the declarant — should match {@code Report.reportDocument.issuer.id.value}. */
  private String sgEntity;

  /** Period this report covers (start). Same value as {@code transactionsReport.reportPeriod.startDate}. */
  private LocalDate periodStartDate;

  /** Period this report covers (end). */
  private LocalDate periodEndDate;

  /** Submission status (e.g. {@code DRAFT}, {@code SUBMITTED}, {@code ACCEPTED}, {@code REJECTED}). */
  private String status;

  /** {@code IN} / {@code RE} (initial vs. corrective). Mirrors {@code Report.reportDocument.typeCode}. */
  private String transmissionType;

  /** When the report was created. */
  private LocalDate createdDate;

  /** Last update timestamp. */
  private LocalDate lastUpdatedDate;

  /** Created-by user (login). */
  private String createdByUser;

  /** Last-updated-by user (login). */
  private String lastUpdatedByUser;

  /** Soft-delete marker. */
  private boolean isDeleted;

  /** The actual Flux 10 payload — what gets serialised to XML and submitted. */
  private Report report;
}
