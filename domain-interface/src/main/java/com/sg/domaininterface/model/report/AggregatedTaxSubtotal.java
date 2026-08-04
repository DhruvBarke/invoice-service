package com.sg.domaininterface.model.report;

import java.math.BigDecimal;
import lombok.*;

/**
 * Per-rate subtotal inside an {@link AggregatedTransactions} block (TG-32).
 * The naming differs slightly from the invoice-level {@link TaxSubTotal} —
 * the schema uses {@code TaxPercent} / {@code TaxTotal} on the aggregated
 * path rather than {@code TaxableAmount} / {@code TaxAmount}.
 *
 * <ul>
 *   <li>{@code TaxPercent} (TT-86) — VAT rate as percent.</li>
 *   <li>{@code TaxableAmount} (TT-87) — total taxable base at this rate
 *       across the aggregated transactions.</li>
 *   <li>{@code TaxTotal} (TT-88) — total VAT at this rate.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class AggregatedTaxSubtotal {
  /** TT-86 — VAT rate (%). */
  private BigDecimal taxPercent;
  /** TT-87 — aggregated taxable amount at this rate. */
  private BigDecimal taxableAmount;
  /** TT-88 — aggregated VAT at this rate. */
  private BigDecimal taxTotal;
}
