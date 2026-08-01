package com.example.invoice.mapper.report.model;

import lombok.*;

/**
 * The seller party on a Flux 10.1 invoice (TG-12). Mandatory.
 *
 * <ul>
 *   <li>{@code CompanyId} (TT-33 + TT-33-1, BT-30/BT-31/BT-27) — supplier
 *       identifier with ISO 6523 scheme. Valid scheme codes:
 *       <ul>
 *         <li>{@code 0002} — SIREN (9 digits, FR)</li>
 *         <li>{@code 0223} — UE hors France (intra-EU VAT id, ≤ 18 chars)</li>
 *         <li>{@code 0227} — Hors UE (≤ 18 chars: country code + 16 chars of legal name)</li>
 *         <li>{@code 0228} — RIDET (Nouvelle-Calédonie, 9 or 10 chars)</li>
 *         <li>{@code 0229} — TAHITI (9 chars)</li>
 *       </ul>
 *   </li>
 *   <li>{@code TaxRegistrationId} (TT-34 + TT-34-0, BT-31) — VAT identifier,
 *       qualifyingId fixed to {@code "VAT"} when schemeId is {@code 0002} or
 *       {@code 0223}.</li>
 *   <li>{@code PostalAddress} (TG-13) — at least country code.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Seller {
  /** TT-33 / TT-33-1 — registration number + scheme. */
  private SchemedIdentifier companyId;
  /** TT-34 / TT-34-0 — VAT id + qualifyingId (optional). */
  private QualifiedIdentifier taxRegistrationId;
  /** TG-13 — postal address (country code minimum). */
  private PostalAddress postalAddress;
}
