package com.example.invoice.service.domain.model.report;

import lombok.*;

/**
 * Business process control block (TG-10) — required on every {@code Invoice}.
 *
 * <ul>
 *   <li>{@code ID} (TT-28, BT-23) — billing-framework code, e.g. {@code B1} /
 *       {@code S1} / {@code M1} (initial invoice for goods / services / mixed),
 *       {@code B7}/{@code S7} (already e-reported), etc.</li>
 *   <li>{@code TypeID} (TT-29, BT-24) — profile identifier URN (e.g.
 *       {@code urn.cpro.gouv.fr:1p0:ereporting}).</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class BusinessProcess {
  /** TT-28 — invoice-framework code (≤ 3 chars). */
  private String id;
  /** TT-29 — profile URN (≤ 255 chars). */
  private String typeId;
}
