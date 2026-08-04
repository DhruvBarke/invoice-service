package com.sg.domaininterface.model.report;

import java.time.LocalDateTime;
import lombok.*;

/**
 * Wrapper for {@code ReportDocument/IssueDateTime} (TG-1). The single child
 * {@code DateTimeString} (TT-3) carries the creation moment formatted as
 * {@code AAAAMMJJHHMMSS} (length 14).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class IssueDateTime {
  /** TT-3 — creation date/time of the report. */
  private LocalDateTime dateTimeString;
}
