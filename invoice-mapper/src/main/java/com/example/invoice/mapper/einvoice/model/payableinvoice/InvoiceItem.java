package com.example.invoice.mapper.einvoice.model.payableinvoice;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One fee line inside an invoice — the source of a UBL {@code InvoiceLine}.
 *
 * <p>Reconstructed from mapper access patterns in A's {@code LineItemMapper} (see
 * {@code invoice-service-mapping/src/main/java/com/sg/domaininterface/mapper/einvoice/}). The
 * original {@code InvoiceItem} lived in the upstream invoice-service host. Fields cover exactly
 * the getters/setters the mappers touch:
 *
 * <ul>
 *   <li>{@code invoiceItemId}, {@code invReferenceSg} — line identity + back-reference to
 *       {@code InvoicePayableModel.invoiceReference}</li>
 *   <li>{@code feeAmount}, {@code feeCurrency}, {@code feeType} — the amount, currency and type
 *       code of the fee</li>
 *   <li>{@code itemDescription} — human-readable label (populated from provider taxonomy)</li>
 *   <li>{@code groupingKey}, {@code natureOfExpense} — categorisation surface used by the
 *       outbound mapping to group lines and by the inbound mapping to preserve categorisation</li>
 *   <li>{@code notionQuantity} — the {@code cbc:InvoicedQuantity} value (typically 1)</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceItem implements Serializable {

  private static final long serialVersionUID = 1L;

  private UUID invoiceItemId;
  private String invReferenceSg;

  private BigDecimal feeAmount;
  private String feeCurrency;
  private String feeType;

  private String itemDescription;
  private String groupingKey;
  private String natureOfExpense;
  private BigDecimal notionQuantity;
}
