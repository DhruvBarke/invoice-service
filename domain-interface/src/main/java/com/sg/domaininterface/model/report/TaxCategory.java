package com.sg.domaininterface.model.report;

import java.math.BigDecimal;
import lombok.*;

/**
 * VAT category descriptor on a {@link TaxSubTotal} (TT-194).
 *
 * <ul>
 *   <li>{@code Code} (TT-56, BT-118) — UNCL 5305 category code, e.g.
 *       {@code S} (standard), {@code Z} (zero), {@code E} (exempt),
 *       {@code AE} (reverse charge), {@code K} (intra-EU supply),
 *       {@code G} (export outside EU).</li>
 *   <li>{@code Percent} (TT-57, BT-119) — applicable VAT rate (% as decimal,
 *       e.g. {@code 20} or {@code 5.5}).</li>
 *   <li>{@code TaxExemptionReason} (TT-58, BT-120) — free-text reason
 *       (required when the category is {@code E} / {@code AE} / {@code K} /
 *       {@code G} or similar exempt).</li>
 *   <li>{@code TaxExemptionReasonCode} (TT-59, BT-121) — coded reason
 *       (CEF VATEX codes).</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class TaxCategory {
  /** TT-56 — UNCL 5305 category code. */
  private String code;
  /** TT-57 — VAT rate as a percent. */
  private BigDecimal percent;
  /** TT-58 — exemption reason (free text). */
  private String taxExemptionReason;
  /** TT-59 — exemption reason code (VATEX). */
  private String taxExemptionReasonCode;
}
