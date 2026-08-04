package com.example.invoice.service.domain.model.report;

import lombok.*;

/**
 * Header note on an invoice (TG-9). Multiple instances allowed per invoice.
 *
 * <ul>
 *   <li>{@code Subject} (TT-26, BT-21) — UNTDID 4451 subject code (e.g. {@code AAB},
 *       {@code TXD}, {@code BLU}). 3 chars max.</li>
 *   <li>{@code Content} (TT-27, BT-22) — the note text up to 1024 chars.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class IncludedNote {
  /** TT-26 — UNTDID 4451 subject code. */
  private String subject;
  /** TT-27 — note content. */
  private String content;
}
