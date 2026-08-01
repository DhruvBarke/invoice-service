/**
 * Invoice facade mappers: translate party registration details into invoice domain objects.
 *
 * <p><b>One dependency, enforced.</b> {@code invoice-service-domain} and nothing else — no cache,
 * no alerting, no third-parties, no DataSource, no Spring. A maven-enforcer
 * {@code bannedDependencies} rule fails the build on any addition, because sibling modules in one
 * repository drift together within a year if nothing objects, and the resulting coupling is
 * invisible until someone needs a database to run a mapper unit test.
 *
 * <p><b>What that buys.</b> A facade bean is constructible from a four-line stub, so mapper tests
 * need no referential, no database and no mail server. See {@code StubLookupExample} in the tests.
 *
 * <p><b>Sub-packages.</b> Two vendored mapping stacks share this module's referential-
 * independence guarantee:
 *
 * <ul>
 *   <li>{@code einvoice/} — {@code InvoicePayable ⇄ Invoice} (UBL). Facade:
 *       {@link com.example.invoice.mapper.einvoice.EInvoiceMappingService}. Vendored from A's
 *       {@code invoice-service-mapping/src/main/java/com/sg/domaininterface/mapper/einvoice/},
 *       with @Mapper stripped, {@code PartyReferentialClient} replaced by
 *       {@link com.example.invoice.service.domain.port.in.PartyRegistrationLookup}, and
 *       {@code SgDocV3Client} eliminated (callers pass PDF/Excel bytes directly).</li>
 *   <li>{@code report/} — {@code InvoicePayable → Flux 10.1 ReportModel}. Facade:
 *       {@link com.example.invoice.mapper.report.ReportMappingService}. Vendored from A's
 *       {@code invoice-service-mapping/src/main/java/com/sg/domaininterface/mapper/report/},
 *       with @Mapper stripped and {@code PartyReferentialClient} replaced by
 *       {@link com.example.invoice.service.domain.port.in.PartyRegistrationLookup}.</li>
 * </ul>
 *
 * <p><b>Name-collision note.</b> This package contains a {@link ReportFacadeMapper} that
 * queries invoice-service for report-consumption use cases; the sub-package
 * {@code com.example.invoice.mapper.report} also contains a {@code ReportFacadeMapper} that
 * <em>builds</em> Flux 10 reports. Different classes, different jobs — pick by
 * fully-qualified name at the injection site.
 *
 * <p><b>Always use the golden details.</b> A record may describe a duplicate elementary party;
 * invoice registration keys on {@code goldenBdrId}. Mappers must not fall back to {@code elemBdrId} —
 * the {@code GOLDEN_PARTY_MISMATCH} rule exists precisely to surface when the two differ.
 *
 * <p><b>Handling failure.</b> {@code PartyRegistrationUnavailableException} carries a reason with a
 * {@code retryable} flag and, where one exists, a quarantine reference an operator can quote. Surface
 * that reference rather than a bare failure: it is the difference between "invoice rejected" and
 * "invoice rejected, fix row 4471".
 *
 * @readme.module Invoice Facade Mappers
 * @readme.order 0
 * @readme.depends invoice-service-domain — and nothing else, enforced at build time
 */
package com.example.invoice.mapper;
