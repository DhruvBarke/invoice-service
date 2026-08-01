package com.example.invoice.mapper.report.model;

import java.time.LocalDate;
import java.util.List;
import lombok.*;

/**
 * Per-invoice payload of a Flux 10.1 transmission (TG-8). This is the
 * Java mirror of {@code /Report/TransactionsReport/Invoice}.
 *
 * <p>This class lives in {@code com.sg.domaininterface.model.report} and is
 * intentionally named {@code Invoice} — do not confuse with the einvoice
 * UBL {@code com.socgen.feesone.commons.models.einvoice.invoice.Invoice}.
 * Both names are valid within their respective packages; import the right
 * one explicitly.
 *
 * <p>Field map (selected — see each sub-type for full detail):
 * <ul>
 *   <li>Header: {@code id} (TT-19, BT-1), {@code issueDate} (TT-20, BT-2),
 *       {@code typeCode} (TT-21, BT-3, UNTDID 1001), {@code currencyCode}
 *       (TT-22, BT-5, ISO 4217), {@code dueDate} (TT-201, BT-9),
 *       {@code taxDueDateTypeCode} (TT-24, BT-8).</li>
 *   <li>Header groups: {@code includedNote} (BG-1),
 *       {@code businessProcess} (BG-2), {@code referencedDocument} (BG-3),
 *       {@code seller} (BG-4), {@code buyer} (BG-7),
 *       {@code sellerTaxRepresentative} (BG-11), {@code delivery} (BG-13),
 *       {@code invoicePeriod} (BG-14).</li>
 *   <li>Totals: {@code discounts} (BG-20), {@code charges} (BG-21),
 *       {@code monetaryTotal} (BG-22), {@code taxSubTotal} (BG-23, ≥ 1).</li>
 *   <li>Lines: {@code line} (BG-25, 0..n).</li>
 * </ul>
 *
 * <p>{@code discounts} and {@code charges} mirror the two
 * {@code AllowanceCharge} groups in the schema (TG-20 vs TG-21,
 * distinguished only by the {@code @ChargeIndicator} attribute on the wire).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Invoice {
  // ── Header ──────────────────────────────────────────────────────────────
  /** TT-19 — invoice id (≤ 35 chars). */
  private String id;
  /** TT-20 — issue date. */
  private LocalDate issueDate;
  /** TT-21 — UNTDID 1001 invoice-type code (e.g. 380 / 381 / 384). */
  private String typeCode;
  /** TT-22 — ISO 4217 currency code. */
  private String currencyCode;
  /** TT-201 — due date (optional). */
  private LocalDate dueDate;
  /** TT-24 — VAT due-date type code (optional, e.g. {@code 3} for VAT-on-debits). */
  private String taxDueDateTypeCode;

  // ── Header groups ───────────────────────────────────────────────────────
  /** TG-9 — header-level notes. */
  private List<IncludedNote> includedNote;
  /** TG-10 — business process (cadre de facturation + profile URN). */
  private BusinessProcess businessProcess;
  /** TG-11 — references to prior invoices (corrective / credit note). */
  private List<ReferencedDocument> referencedDocument;
  /** TG-12 — seller. */
  private Seller seller;
  /** TG-14 — buyer (optional for B2C, mandatory for B2Bi). */
  private Buyer buyer;
  /** TG-16 — seller tax representative (international B2B). */
  private SellerTaxRepresentative sellerTaxRepresentative;
  /** TG-17 — delivery info (0..n). */
  private List<Delivery> delivery;
  /** TG-18 — billing period. */
  private InvoicePeriod invoicePeriod;

  // ── Totals ──────────────────────────────────────────────────────────────
  /** TG-20 — header-level discounts ({@code @ChargeIndicator=false}). */
  private List<AllowanceCharge> discounts;
  /** TG-21 — header-level surcharges ({@code @ChargeIndicator=true}). */
  private List<AllowanceCharge> charges;
  /** TG-22 — monetary totals. */
  private MonetaryTotal monetaryTotal;
  /** TG-23 — per-rate tax subtotals (≥ 1). */
  private List<TaxSubTotal> taxSubTotal;

  // ── Lines ───────────────────────────────────────────────────────────────
  /** TG-24 — invoice lines (0..n). */
  private List<InvoiceLine> line;
}
