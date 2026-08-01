package com.example.invoice.mapper.report.model;

import lombok.*;

/**
 * Identifier with a {@code @schemeId} attribute. Used for any field that
 * carries an ISO 6523 ICD scheme on the wire:
 * <ul>
 *   <li>{@code Sender/Id} (TT-7 + TT-8) — typically schemeId {@code "0238"}.</li>
 *   <li>{@code Issuer/Id} (TT-12 + TT-13) — schemeId {@code "0002"} (SIREN).</li>
 *   <li>{@code Seller/CompanyId} (TT-33 + TT-33-1) — schemeId one of
 *       {@code 0002 / 0223 / 0227 / 0228 / 0229}.</li>
 *   <li>{@code Buyer/CompanyId} (TT-36 + TT-37) — same scheme family.</li>
 *   <li>{@code References/ReportId} (TT-5 + TT-6) — IN/RE/CO/MO.</li>
 *   <li>{@code SellerTaxRepresentative/TaxRegistrationId/@schemeId} (TT-40).</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class SchemedIdentifier {
  /** The identifier value. */
  private String value;
  /** ISO 6523 scheme identifier carried as {@code @schemeId} on the wire. */
  private String schemeId;
}
