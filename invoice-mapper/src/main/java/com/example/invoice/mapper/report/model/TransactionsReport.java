package com.example.invoice.mapper.report.model;

import java.util.List;
import lombok.*;

/**
 * Flux 10.1 / 10.3 payload (TB-2). Exactly one of {@code invoice} or
 * {@code transactions} is populated per transmission, depending on the
 * sub-flow:
 *
 * <ul>
 *   <li>Flux 10.1 — per-invoice rows ({@code invoice} populated; this is
 *       what the {@code InvoicePayable → ReportModel} mapper builds).</li>
 *   <li>Flux 10.3 — aggregated rows ({@code transactions} populated; used
 *       for B2C / international where per-invoice reporting isn't required).</li>
 * </ul>
 *
 * <p>The PA validates that mixing the two yields a single rejection, so
 * higher-level orchestration code must choose between them per transmission.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class TransactionsReport {
  /** TG-7 — reporting period. */
  private ReportPeriod reportPeriod;
  /** TG-8 — per-invoice payload (Flux 10.1). */
  private List<Invoice> invoice;
  /** TG-31 — aggregated payload (Flux 10.3). */
  private List<AggregatedTransactions> transactions;
}
