package com.example.invoice.service.domain.model.report;

import lombok.*;

/**
 * The buyer party on a Flux 10.1 invoice (TG-14). Optional at the group
 * level (B2C invoices may omit it), but mandatory for B2Bi where
 * {@code CompanyId} (TT-36, BT-47) carries the buyer SIREN.
 *
 * <p>Same scheme conventions as {@link Seller}'s {@code companyId} —
 * {@code 0002} for SIREN, {@code 0223} / {@code 0227} / {@code 0228} /
 * {@code 0229} for the alternate registrations.
 *
 * <ul>
 *   <li>{@code CompanyId} (TT-36 + TT-37, BT-47) — buyer identifier + scheme.</li>
 *   <li>{@code TaxRegistrationId} (TT-38 + TT-38-0, BT-48) — VAT id when
 *       schemeId is {@code 0002}/{@code 0223}.</li>
 *   <li>{@code PostalAddress} (TG-15) — at least country code.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Buyer {
  /** TT-36 / TT-37 — registration number + scheme. */
  private SchemedIdentifier companyId;
  /** TT-38 / TT-38-0 — VAT id + qualifyingId (optional). */
  private QualifiedIdentifier taxRegistrationId;
  /** TG-15 — postal address (country code minimum). */
  private PostalAddress postalAddress;
}
