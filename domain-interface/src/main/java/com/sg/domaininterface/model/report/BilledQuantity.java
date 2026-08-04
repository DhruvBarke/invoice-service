package com.sg.domaininterface.model.report;

import java.math.BigDecimal;
import lombok.*;

/**
 * Line quantity (TT-62) with a unit code (TT-63).
 *
 * <ul>
 *   <li>{@code value} (TT-62, BT-129) — quantity (decimal).</li>
 *   <li>{@code unitCode} (TT-63, BT-130) — UN/ECE Recommendation 20 unit
 *       code (e.g. {@code EA} = each, {@code HUR} = hour, {@code KGM} = kg).</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class BilledQuantity {
  /** TT-62 — billed quantity. */
  private BigDecimal value;
  /** TT-63 — unit code. */
  private String unitCode;
}
