package com.sg.jpa.entity;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One {@code publicinvoice.t_invoice_document_payable} row.
 *
 * <p>Field-for-field with the target JPA entity, minus the annotations — see
 * {@link InvoicePayableEntity} for why.
 *
 * <p><b>Metadata only. There is no content column, by design.</b> The bytes live in SGDoc and
 * {@link #sgDocId} is the handle. It stays null until an upload has returned one, and a null
 * handle is not a broken row — it is the honest record that a document arrived and its content
 * is not yet retrievable. That distinction is what lets the attachment rules tell "nothing was
 * sent" apart from "it was sent and the upload failed", which are different failures deserving
 * different lifecycle events.
 *
 * <p>{@code arrivalTime} is String in the target entity despite being a timestamp. Mirrored.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceDocumentPayableEntity {

  private UUID id;

  /** Correlates to the parent's {@code invoiceReference}. No foreign key. */
  private String invoiceReference;

  /** SGDoc's handle for the content. Null until the upload succeeds. */
  private String sgDocId;

  private String documentName;

  /** PDF / TRADE_FILE / OTHER — what it is, for the rules' benefit. */
  private String documentType;

  /** Which channel delivered it: MULTIPART or EINVOICE_BODY. */
  private String incomingLine;

  /** The MIME type as the sender declared it. */
  private String format;

  private String actionPerformedBy;
  private String comment;
  private boolean isDeleted;

  private String arrivalTime;
  private String documentReference;
  private String documentStatus;

  private String senderAddress;
  private Boolean registrationStatus;
  private String registrationType;
  private String subject;
  private String body;

  private LocalDateTime createdDate;
  private LocalDateTime lastUpdatedDate;
  private String lastUpdatedByUser;

  private String parserId;
  private String parserResponse;
  private String parserSource;
}
