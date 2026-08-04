package com.example.invoice.service.domain.model.report;

import lombok.*;

/**
 * The transmission header (TB-1) — required at the root of every Flux 10
 * transmission, regardless of whether the payload is transactions or
 * payments.
 *
 * <p>Field map:
 * <ul>
 *   <li>{@code Id} (TT-1, 1..1) — unique per period per declarant.</li>
 *   <li>{@code Name} (TT-2, 0..1) — free-text document name.</li>
 *   <li>{@code IssueDateTime/DateTimeString} (TG-1 + TT-3, 1..1) — creation timestamp.</li>
 *   <li>{@code TypeCode} (TT-4, 1..1) — one of {@code IN/RE/CO/MO}.</li>
 *   <li>{@code References} (TG-2, 0..1) — pointer to a prior transmission.</li>
 *   <li>{@code Sender} (TG-3, 1..1) — the PA platform that submits the transmission.</li>
 *   <li>{@code Issuer} (TG-5, 1..1) — the declarant SIREN owner.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class ReportDocument {
  /** TT-1 — transmission identifier (≤ 50 chars). */
  private String id;
  /** TT-2 — optional document name (≤ 150 chars). */
  private String name;
  /** TG-1 — creation timestamp wrapper. */
  private IssueDateTime issueDateTime;
  /** TT-4 — transmission type code: {@code IN} / {@code RE} / {@code CO} / {@code MO}. */
  private String typeCode;
  /** TG-2 — reference to a prior transmission (only for corrections). */
  private References references;
  /** TG-3 — the platform (PA) that submitted the transmission. */
  private Party sender;
  /** TG-5 — the declarant whose SIREN owns the transmission. */
  private Party issuer;
}
