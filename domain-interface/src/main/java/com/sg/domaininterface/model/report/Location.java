package com.sg.domaininterface.model.report;

import lombok.*;

/**
 * Delivery address used both at invoice header (TG-19, BG-15) and at line
 * level (TG-42). Country code is mandatory in both contexts; the rest is
 * optional.
 *
 * <ul>
 *   <li>{@code LineOne / LineTwo / LineThree} (TT-103/TT-104/TT-105 — BT-75/BT-76/BT-165)</li>
 *   <li>{@code CityName} (TT-106, BT-77)</li>
 *   <li>{@code PostalZone} (TT-107, BT-78)</li>
 *   <li>{@code CountrySubentity} (TT-108, BT-79) — region / state / county.</li>
 *   <li>{@code CountryId} (TT-44 header / TT-307 line, BT-80) — ISO 3166 alpha-2.</li>
 * </ul>
 *
 * <p>The line-level form (TG-42) uses paths TT-303 / TT-304 / TT-305 /
 * TT-306 / TT-307 — same field set, distinct numbering.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Location {
  /** TT-103 / TT-303 — line 1 of the address. */
  private String lineOne;
  /** TT-104 — line 2 of the address (header only). */
  private String lineTwo;
  /** TT-105 — line 3 of the address (header only). */
  private String lineThree;
  /** TT-106 / TT-304 — city name. */
  private String cityName;
  /** TT-107 / TT-305 — postal zone. */
  private String postalZone;
  /** TT-108 / TT-306 — country subdivision (region / state / county). */
  private String countrySubentity;
  /** TT-44 / TT-307 — ISO 3166 alpha-2 country code. */
  private String countryId;
}
