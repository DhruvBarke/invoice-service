package com.sg.domaininterface.model.report;

import lombok.*;

/**
 * Optional seller tax-representative block (TG-16). Used for international
 * B2B where a fiscal representative shoulders the VAT obligation in France
 * on behalf of a non-FR seller.
 *
 * <ul>
 *   <li>{@code TaxRegistrationId} (TT-122, BT-63) — the representative's VAT id.</li>
 *   <li>{@code TaxRegistrationId/@schemeId} (TT-40, BT-63-1) — modelled here
 *       as {@code schemeId} on the {@link SchemedIdentifier}; the wire
 *       attribute name is {@code @schemeId}.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class SellerTaxRepresentative {
  /** TT-122 / TT-40 — VAT id + schemeId of the fiscal representative. */
  private SchemedIdentifier taxRegistrationId;
}
