package com.example.invoice.service.domain.model.report;

import lombok.*;

/**
 * Product / service descriptor on an invoice line (TG-30, BG-31). Only the
 * name is mandatory in Flux 10.1.
 *
 * <ul>
 *   <li>{@code Name} (TT-76, BT-153) — item / service name.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Product {
  /** TT-76 — product / service name. */
  private String name;
}
