package com.example.invoice.mapper.report.model;

import lombok.*;

/**
 * Header-level postal address (TG-13 for Seller / TG-15 for Buyer). At this
 * level only the country code matters — full street-and-city detail lives on
 * the delivery {@link Location} (TG-19).
 *
 * <ul>
 *   <li>{@code CountryId} (TT-35 for Seller, TT-39 for Buyer) — ISO 3166
 *       alpha-2 code.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class PostalAddress {
  /** TT-35 / TT-39 — ISO 3166 alpha-2 country code. */
  private String countryId;
}
