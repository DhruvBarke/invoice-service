package com.example.invoice.service.domain.model.report;

import java.time.LocalDate;
import lombok.*;

/**
 * Invoice billing period (TG-18 header level, TG-25 line level).
 *
 * <ul>
 *   <li>{@code StartDate} (TT-42 / TT-65, BT-73 / BT-134) — period start,
 *       {@code AAAAMMJJ}.</li>
 *   <li>{@code EndDate} (TT-43 / TT-66, BT-74 / BT-135) — period end,
 *       {@code AAAAMMJJ}.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class InvoicePeriod {
  /** TT-42 / TT-65 — period start. */
  private LocalDate startDate;
  /** TT-43 / TT-66 — period end. */
  private LocalDate endDate;
}
