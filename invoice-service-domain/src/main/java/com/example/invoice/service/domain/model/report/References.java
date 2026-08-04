package com.example.invoice.service.domain.model.report;

import lombok.*;

/**
 * Optional reference to a prior transmission (TG-2). Only used when
 * correcting a previous report (e.g. {@code TypeCode=RE}); the
 * {@code ReportId} carries the previous transmission's id and its
 * {@code @schemeId} indicates the type of that prior transmission
 * ({@code IN/RE/CO/MO} — TT-6).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class References {
  /** TT-5 (value) + TT-6 (schemeId). */
  private SchemedIdentifier reportId;
}
