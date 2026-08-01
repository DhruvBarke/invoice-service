package com.example.invoice.mapper.report.model;

import java.math.BigDecimal;
import lombok.*;

/**
 * Invoice-level monetary totals (TG-22, BG-22).
 *
 * <ul>
 *   <li>{@code TaxExclusiveAmount} (TT-51, BT-109) — sum of line amounts
 *       net of VAT. Optional only because it's derivable from the line
 *       totals; we model it as nullable to mirror the schema.</li>
 *   <li>{@code TaxAmount} (TT-52 + TT-202, BT-110/BT-111) — total VAT
 *       amount with mandatory {@code @CurrencyCode}. When the invoice
 *       currency differs from EUR this is the EUR-converted amount.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class MonetaryTotal {
  /** TT-51 — tax-exclusive amount. */
  private BigDecimal taxExclusiveAmount;
  /** TT-52 / TT-202 — total VAT amount with explicit currency. */
  private CurrencyAmount taxAmount;
}
