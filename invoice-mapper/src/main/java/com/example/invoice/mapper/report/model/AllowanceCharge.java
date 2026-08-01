package com.example.invoice.mapper.report.model;

import java.math.BigDecimal;
import lombok.*;

/**
 * Discount or charge applied either at invoice header (TG-20 / TG-21) or at
 * line level (TG-26 / TG-27).
 *
 * <p>The {@code @ChargeIndicator} XML attribute distinguishes the two
 * variants — we carry it as a Java {@code boolean}:
 * <ul>
 *   <li>{@code chargeIndicator = false} — discount (TG-20 / TG-26).</li>
 *   <li>{@code chargeIndicator = true} — surcharge (TG-21 / TG-27).</li>
 * </ul>
 *
 * <p>Field map:
 * <ul>
 *   <li>{@code Amount} — TT-45 (BT-92, discount header), TT-48 (BT-99,
 *       surcharge header), TT-67 (BT-136, discount line), TT-68 (BT-141,
 *       surcharge line).</li>
 *   <li>{@code TaxCategoryCode} — TT-46 (BT-95) / TT-49 (BT-102). Header-only.</li>
 *   <li>{@code TaxPercent} — TT-47 (BT-96) / TT-50 (BT-103). Header-only.</li>
 * </ul>
 *
 * <p>Line-level instances only carry {@code Amount}; tax code / percent
 * inherit from the line's {@link TaxCategory}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class AllowanceCharge {
  /** {@code @ChargeIndicator}: {@code false} = discount, {@code true} = surcharge. */
  private boolean chargeIndicator;
  /** Discount/surcharge amount. */
  private BigDecimal amount;
  /** Tax category code (header level only). */
  private String taxCategoryCode;
  /** Tax percent (header level only). */
  private BigDecimal taxPercent;
}
