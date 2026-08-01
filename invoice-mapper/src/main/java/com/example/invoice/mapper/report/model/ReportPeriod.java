package com.example.invoice.mapper.report.model;

import java.time.LocalDate;
import lombok.*;

/**
 * Reporting period for a {@code TransactionsReport} (TG-7). Period bounds
 * follow the VAT declaration cycle and are computed by the PA, not by the
 * declarant.
 *
 * <ul>
 *   <li>{@code StartDate} (TT-17, {@code AAAAMMJJ}).</li>
 *   <li>{@code EndDate} (TT-18, {@code AAAAMMJJ}).</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class ReportPeriod {
  /** TT-17 — period start. */
  private LocalDate startDate;
  /** TT-18 — period end. */
  private LocalDate endDate;
}
