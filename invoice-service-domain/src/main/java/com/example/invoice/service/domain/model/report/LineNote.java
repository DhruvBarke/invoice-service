package com.example.invoice.service.domain.model.report;

import lombok.*;

/**
 * Note attached to a single invoice line (TT-61). Distinct from header
 * {@link IncludedNote} because the wire field names differ: lines use
 * {@code Code} + {@code Comment} (TT-61-0 / TT-61-1), header uses
 * {@code Subject} + {@code Content}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class LineNote {
  /** TT-61-0 — line note code (EXT-FR-FE-183). */
  private String code;
  /** TT-61-1 — line note text (BT-127). */
  private String comment;
}
