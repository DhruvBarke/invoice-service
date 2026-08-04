package com.sg.domaininterface.model.report;

import lombok.*;

/**
 * Shared shape for {@code Sender} ({@code TG-3}) and {@code Issuer}
 * ({@code TG-5}). The two have identical sub-fields:
 *
 * <table>
 *   <caption>Field map</caption>
 *   <tr><th>Path</th><th>Sender (TG-3)</th><th>Issuer (TG-5)</th></tr>
 *   <tr><td>{@code Id}</td><td>TT-8</td><td>TT-13</td></tr>
 *   <tr><td>{@code Id/@schemeId}</td><td>TT-7</td><td>TT-12</td></tr>
 *   <tr><td>{@code Name}</td><td>TT-9</td><td>TT-14</td></tr>
 *   <tr><td>{@code RoleCode}</td><td>TT-10</td><td>TT-15</td></tr>
 *   <tr><td>{@code URIUniversalCommunication/URIID}</td><td>TT-11</td><td>TT-16</td></tr>
 * </table>
 *
 * <p>Conventions:
 * <ul>
 *   <li>Sender (PA platform) — schemeId {@code "0238"}, roleCode {@code "WK"},
 *       id is the 4-char platform matricule.</li>
 *   <li>Issuer (declarant) — schemeId {@code "0002"}, roleCode {@code "BY"}
 *       (acheteur) or {@code "SE"} (vendeur), id is the 9-digit SIREN.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Party {
  /** Identifier value + scheme. TT-7/TT-8 for Sender, TT-12/TT-13 for Issuer. */
  private SchemedIdentifier id;
  /** Legal name. TT-9 for Sender, TT-14 for Issuer. */
  private String name;
  /** UNCL 3035 role code. {@code "WK"} for Sender; {@code "BY"} or {@code "SE"} for Issuer. */
  private String roleCode;
  /** Optional CEF address (TG-4 / TG-6). */
  private UriUniversalCommunication uriUniversalCommunication;
}
