/**
 * Flux 10.1 ReportModel — 34 classes forming the {@link ReportModel} graph.
 *
 * <p>Vendored from A's {@code com.sg.einvoicing.domain.model.report} package (see
 * {@code domain-interface/src/main/java/com/sg/einvoicing/domain/model/report/} in the source
 * einvoice-service repo). One omission and one universal edit, mirroring the choice made for
 * the sibling {@code einvoice.model.invoice} package:
 *
 * <ul>
 *   <li><b>Omitted:</b> {@code Flux10DateSerde}. It is the Jackson serde pair that formats
 *       {@code LocalDate} as {@code AAAAMMJJ} on the wire — only relevant when serialising to
 *       JSON/XML. Callers who need that wire format wrap this module's date fields at their
 *       own layer.</li>
 *   <li><b>Universal edit:</b> Jackson annotations stripped
 *       ({@code @JsonIgnoreProperties}, {@code @JsonCreator}, {@code @JsonSerialize},
 *       {@code @JsonDeserialize}). No runtime Jackson dependency.</li>
 * </ul>
 *
 * <p>Report structure (top-down): {@link ReportModel} envelope → {@link Report} → three
 * top-level groups {@link ReportDocument} (TG-1), {@link TransactionsReport} (TG-2, contains
 * invoice list), {@link AggregatedTransactions} (TG-31). See A's original code for the full
 * BT/BG/TT/TG mapping table.
 */
package com.example.invoice.service.domain.model.report;
