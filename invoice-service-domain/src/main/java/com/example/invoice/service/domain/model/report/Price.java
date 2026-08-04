package com.example.invoice.service.domain.model.report;

import java.math.BigDecimal;
import lombok.*;

/**
 * Unit-price block for a single invoice line (TG-28, BG-29).
 *
 * <ul>
 *   <li>{@code PriceAmount} (TT-69, BT-146) — unit price (per
 *       {@link BilledQuantity#unitCode}).</li>
 *   <li>{@code AllowanceChargeAmount} (TT-70, BT-147) — unit-level discount.</li>
 *   <li>{@code AllowanceChargeBaseAmount} (TT-71, BT-148) — gross unit price
 *       before the discount.</li>
 * </ul>
 *
 * <p>{@code PriceAmount + AllowanceChargeAmount = AllowanceChargeBaseAmount}
 * when discounts apply.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Price {
  /** TT-69 — net unit price. */
  private BigDecimal priceAmount;
  /** TT-70 — unit-level discount. */
  private BigDecimal allowanceChargeAmount;
  /** TT-71 — gross unit price before unit-level discount. */
  private BigDecimal allowanceChargeBaseAmount;
}
