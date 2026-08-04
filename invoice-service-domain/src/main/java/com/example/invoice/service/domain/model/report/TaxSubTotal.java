package com.example.invoice.service.domain.model.report;

import java.math.BigDecimal;
import lombok.*;

/**
 * Per-VAT-rate breakdown (TG-23, BG-23). At least one entry required per
 * invoice; multiple if the invoice mixes VAT rates / exemption categories.
 *
 * <ul>
 *   <li>{@code TaxableAmount} (TT-54, BT-116) — net amount taxed at this rate.</li>
 *   <li>{@code TaxAmount} (TT-55, BT-117) — VAT amount for this subtotal.</li>
 *   <li>{@code TaxCategory} (TT-194) — wraps Code/Percent/exemption info.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class TaxSubTotal {
  /** TT-54 — taxable amount for this rate / category. */
  private BigDecimal taxableAmount;
  /** TT-55 — VAT amount for this rate / category. */
  private BigDecimal taxAmount;
  /** TT-194 — category code, percent, exemption reason / code. */
  private TaxCategory taxCategory;
}
