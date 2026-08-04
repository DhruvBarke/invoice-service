package com.example.invoice.service.domain.model.report;

import lombok.*;

/**
 * Root of a Flux 10 transmission, mirroring {@code /Report}. Always carries
 * the {@link ReportDocument} header; carries a {@link TransactionsReport}
 * for Flux 10.1 / 10.3. {@code PaymentsReport} (TB-3, Flux 10.2 / 10.4)
 * is intentionally not modelled here — payment reporting is out of scope
 * for the {@link com.sg.domaininterface.model.payableinvoice.InvoicePayable}
 * mapping.
 *
 * <p>Per the PA rule, a single transmission either reports transactions
 * (TB-1 + TB-2) <em>or</em> payments (TB-1 + TB-3). Carrying both fields
 * here would let invalid documents be constructed; the absence of a
 * {@code paymentsReport} field on this class enforces the "Flux 10.1 only"
 * scope at compile time.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Report {
  /** TB-1 — transmission header (mandatory). */
  private ReportDocument reportDocument;
  /** TB-2 — Flux 10.1 / 10.3 payload (mandatory for our use). */
  private TransactionsReport transactionsReport;
}
