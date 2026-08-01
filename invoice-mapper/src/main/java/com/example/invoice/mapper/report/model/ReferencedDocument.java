package com.example.invoice.mapper.report.model;

import java.time.LocalDate;
import lombok.*;

/**
 * Reference to a prior invoice (TG-11). Required when the current invoice is
 * a corrective ({@code TypeCode=384/471/472/473}) or a credit note. Multiple
 * references allowed for credit notes.
 *
 * <ul>
 *   <li>{@code ID} (TT-30, BT-25) — original invoice id (≤ 35 chars).</li>
 *   <li>{@code IssueDate} (TT-31, BT-26) — original invoice issue date,
 *       {@code AAAAMMJJ}.</li>
 * </ul>
 *
 * <p>Also used at line-level (TG-40 — TT-300/TT-301) — the shape is the same.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class ReferencedDocument {
  /** TT-30 / TT-300 — prior invoice id. */
  private String id;
  /** TT-31 / TT-301 — prior invoice issue date. */
  private LocalDate issueDate;
}
