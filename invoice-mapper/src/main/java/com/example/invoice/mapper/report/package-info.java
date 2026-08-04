/**
 * {@code InvoicePayable → ReportModel} mapping — plain-Java port of A's
 * {@code com.sg.domaininterface.mapper.report} package. One-way flow: SG assembles a Flux
 * 10.1 report from the invoice-service's payable graph and submits to the PA platform. The
 * reverse direction (PA → SG status feedback) lives outside this module.
 *
 * <p><b>What changed vs A.</b>
 *
 * <ul>
 *   <li><b>@Mapper annotations removed.</b> All mappers are now plain {@code final} classes.
 *       Stateless sub-mappers ({@link ReportPartyMapper}, {@link ReportTotalsMapper},
 *       {@link ReportLineMapper}, {@link ReportInvoiceMapper}, {@link ReportDocumentMapper})
 *       are static-method utilities; the {@link ReportFacadeMapper} composer takes its
 *       collaborators ({@link com.example.invoice.service.domain.port.in.PartyRegistrationLookup}
 *       and {@link ReportFlowConfig}) via constructor.</li>
 *   <li><b>{@code PartyReferentialClient} replaced by {@link
 *       com.example.invoice.service.domain.port.in.PartyRegistrationLookup}.</b>
 *       {@link ReportPartyMapper#toIssuer(String,
 *       com.example.invoice.service.domain.port.in.PartyRegistrationLookup, String)}
 *       calls the shared referential for the SG legal name that populates
 *       {@code ReportDocument.Issuer.Party.Name} (TT-14). Fields feesone-side
 *       {@code PartyInfo.group / lei / internalCode} carried are not present on
 *       {@link com.example.invoice.service.domain.model.PartyRegistrationDetails}; the
 *       Flux 10 Seller / Buyer schema doesn't carry them either, so no regression.</li>
 *   <li><b>{@code Flux10DateSerde.DATE_TIME_FORMAT} inlined</b> as
 *       {@link ReportDocumentMapper#DATE_TIME_FORMAT}. The full Jackson serde was omitted
 *       from the vendored model (see {@link com.example.invoice.service.domain.model.report.package-info}).</li>
 * </ul>
 *
 * <p><b>Sub-mapper graph.</b>
 * <pre>
 *   ReportMappingService
 *     └── ReportFacadeMapper (ctor: PartyRegistrationLookup, ReportFlowConfig)
 *           ├── ReportDocumentMapper (static; takes lookup + config)
 *           │     └── ReportPartyMapper.toSender / toIssuer (static)
 *           └── ReportInvoiceMapper  (static; takes config)
 *                 ├── ReportPartyMapper.toSeller / toBuyer (static)
 *                 ├── ReportTotalsMapper (static)
 *                 └── ReportLineMapper (static)
 * </pre>
 */
package com.example.invoice.mapper.report;
