package com.example.invoice.mapper.einvoice.model.payableinvoice;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Envelope that pairs an {@link InvoicePayable} payload with the invoice-service metadata the
 * einvoice mappers need to translate to/from UBL.
 *
 * <p>Reconstructed from mapper access patterns in A's {@code EInvoiceFacadeMapper} (see
 * {@code invoice-service-mapping/src/main/java/com/sg/domaininterface/mapper/einvoice/}). The
 * original {@code InvoicePayableModel} class lived in the upstream invoice-service host and was
 * not part of A's tree; this reconstruction covers the getter/setter surface the mappers actually
 * touch:
 *
 * <ul>
 *   <li>{@code invoiceReference}, {@code invoiceDate}, {@code invoiceType}, {@code invoiceStatus}
 *       — invoice header</li>
 *   <li>{@code providerId}, {@code sgEntity}, {@code refCptyId} — party identifiers, resolved
 *       through {@link com.example.invoice.service.domain.port.in.PartyRegistrationLookup}</li>
 *   <li>{@code tradingStartDate}, {@code tradingEndDate} — invoice period (BG-14)</li>
 *   <li>{@code amount}, {@code currency} — line-extension-total-inclusive-of-tax</li>
 *   <li>{@code feeCategory} — routing category (CUS / EXC / CLR / BKP / …)</li>
 *   <li>{@code invoicePayable} — the nested {@link InvoicePayable} carrying every other field</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoicePayableModel implements Serializable {

  private static final long serialVersionUID = 1L;

  private String invoiceReference;
  private LocalDate invoiceDate;
  private String invoiceType;
  private String invoiceStatus;
  private String feeCategory;

  private String providerId;
  private String sgEntity;
  private String refCptyId;

  private LocalDate tradingStartDate;
  private LocalDate tradingEndDate;

  private BigDecimal amount;
  private String currency;

  // Audit fields — populated on write, consumed by the report mapping chain when it stamps
  // ReportModel.createdByUser / lastUpdatedByUser.
  private String createdByUser;
  private String lastUpdatedByUser;

  private InvoicePayable invoicePayable;
}
