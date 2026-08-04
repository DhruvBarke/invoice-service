package com.sg.domaininterface.model.report;

import lombok.*;

/**
 * Identifier with a {@code @qualifyingId} attribute. Used for VAT identifier
 * fields per Flux 10:
 * <ul>
 *   <li>{@code Seller/TaxRegistrationId} (TT-34 + TT-34-0) — qualifyingId
 *       {@code "VAT"} when supplier's scheme is {@code 0002}/{@code 0223}.</li>
 *   <li>{@code Buyer/TaxRegistrationId} (TT-38 + TT-38-0) — same rule.</li>
 *   <li>{@code SellerTaxRepresentative/TaxRegistrationId} (TT-122) — also
 *       VAT-qualified although the attribute name on the wire is {@code @schemeId}
 *       per the spec; modelled here as a separate type because the value of
 *       that attribute is fixed and the field is conceptually a TaxRegistrationId.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class QualifiedIdentifier {
  /** The identifier value (e.g. {@code FR19542058086}). */
  private String value;
  /** Qualifier carried as {@code @qualifyingId} on the wire (expected: {@code VAT}). */
  private String qualifyingId;
}
