package com.example.invoice.mapper.report.model;

import java.time.LocalDate;
import lombok.*;

/**
 * Delivery information (TG-17 at header level, TG-41 at line level). Header
 * usage carries a date (TT-41) and an optional location; line usage adds an
 * optional {@code Name} (TT-302).
 *
 * <ul>
 *   <li>{@code Date} (TT-41, BT-72) — effective delivery date,
 *       {@code AAAAMMJJ}. Header-only.</li>
 *   <li>{@code Name} (TT-302, EXT-FR-FE-149) — recipient name. Line-only.</li>
 *   <li>{@code Location} (TG-19 / TG-42) — delivery address.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Delivery {
  /** TT-41 — effective delivery date (header-level only). */
  private LocalDate date;
  /** TT-302 — recipient name (line-level only). */
  private String name;
  /** TG-19 / TG-42 — delivery address. */
  private Location location;
}
