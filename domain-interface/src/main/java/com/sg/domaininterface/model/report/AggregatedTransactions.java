package com.sg.domaininterface.model.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.*;

/**
 * Aggregated transactions block (TG-31) — the alternate payload of
 * {@link TransactionsReport} for flows that don't ship invoice-by-invoice
 * (B2C, international with no buyer SIREN, ...).
 *
 * <p>The {@link InvoicePayable} → {@link ReportModel} mapper produces
 * per-invoice {@code Invoice} entries (TG-8) instead, so this type is
 * carried for completeness but won't be populated by that mapper.
 *
 * <ul>
 *   <li>{@code Date} (TT-77) — reporting reference date for the aggregate.</li>
 *   <li>{@code TransactionsCurrency} (TT-78) — ISO 4217 currency.</li>
 *   <li>{@code TaxDueDateTypeCode} (TT-80) — optional VAT-on-debits flag.</li>
 *   <li>{@code CategoryCode} (TT-81) — UNCL 5305 VAT category.</li>
 *   <li>{@code TaxExclusiveAmount} (TT-82) — total net amount.</li>
 *   <li>{@code TaxTotal} (TT-83) — total VAT.</li>
 *   <li>{@code TransactionsCount} (TT-85) — optional transaction count.</li>
 *   <li>{@code TaxSubtotal} (TG-32, 1..n) — per-rate breakdown.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class AggregatedTransactions {
  /** TT-77 — aggregation reference date. */
  private LocalDate date;
  /** TT-78 — ISO 4217 currency code. */
  private String transactionsCurrency;
  /** TT-80 — VAT-due-date type code (optional). */
  private String taxDueDateTypeCode;
  /** TT-81 — UNCL 5305 VAT category. */
  private String categoryCode;
  /** TT-82 — aggregated tax-exclusive amount. */
  private BigDecimal taxExclusiveAmount;
  /** TT-83 — aggregated tax total. */
  private BigDecimal taxTotal;
  /** TT-85 — number of transactions in the aggregate (optional). */
  private Integer transactionsCount;
  /** TG-32 — per-rate breakdown (≥ 1). */
  private List<AggregatedTaxSubtotal> taxSubtotal;
}
