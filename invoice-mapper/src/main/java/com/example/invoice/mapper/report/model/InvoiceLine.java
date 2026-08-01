package com.example.invoice.mapper.report.model;

import java.util.List;
import lombok.*;

/**
 * Per-line detail of a Flux 10.1 invoice (TG-24, BG-25). Up to many lines
 * per invoice.
 *
 * <p>Field map:
 * <ul>
 *   <li>{@code Note} (TT-61, 0..n) — line-level notes.</li>
 *   <li>{@code BilledQuantity} (TT-62 + TT-63, 0..1) — quantity with unit code.</li>
 *   <li>{@code ReferencedDocument} (TG-40, 0..1) — line-level prior invoice ref.</li>
 *   <li>{@code Delivery} (TG-41, 0..1) — line-level delivery info.</li>
 *   <li>{@code InvoicePeriod} (TG-25, 0..1) — line-level billing period.</li>
 *   <li>{@code Discounts} (TG-26, 0..n) — line-level discounts
 *       ({@code @ChargeIndicator=false}).</li>
 *   <li>{@code Charges} (TG-27, 0..n) — line-level surcharges
 *       ({@code @ChargeIndicator=true}).</li>
 *   <li>{@code Price} (TG-28, 0..1) — unit price block.</li>
 *   <li>{@code Product} (TG-30, 0..1) — item / service descriptor.</li>
 * </ul>
 *
 * <p>{@link #discounts} and {@link #charges} are separated on the Java side
 * for clarity; the XML serializer collapses them back into the two
 * {@code AllowanceCharge} groups with the proper {@code @ChargeIndicator}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class InvoiceLine {
  /** TT-61 — line notes. */
  private List<LineNote> note;
  /** TT-62 / TT-63 — billed quantity + unit code. */
  private BilledQuantity billedQuantity;
  /** TG-40 — line-level prior invoice reference. */
  private ReferencedDocument referencedDocument;
  /** TG-41 — line-level delivery. */
  private Delivery delivery;
  /** TG-25 — line-level invoice period. */
  private InvoicePeriod invoicePeriod;
  /** TG-26 — line-level discounts (chargeIndicator=false). */
  private List<AllowanceCharge> discounts;
  /** TG-27 — line-level surcharges (chargeIndicator=true). */
  private List<AllowanceCharge> charges;
  /** TG-28 — unit-price block. */
  private Price price;
  /** TG-30 — product / service. */
  private Product product;
}
