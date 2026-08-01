/**
 * einvoice ↔ {@code InvoicePayableModel} mapping — plain-Java port of A's
 * {@code com.sg.domaininterface.mapper.einvoice} package.
 *
 * <p><b>What changed vs A.</b>
 *
 * <ul>
 *   <li><b>@Mapper / @Named / @Context annotations removed.</b> All mappers are now plain
 *       {@code final} classes with static methods (stateless mappers) or one constructor
 *       parameter (stateful: {@link EInvoiceFacadeMapper}, {@link EInvoiceMappingService}).
 *       No MapStruct bean formation; no processor plugin; a mapper is a mapper because it
 *       has {@code toXxx} methods, not because the compile-time framework decided so.</li>
 *   <li><b>{@code PartyReferentialClient} replaced by {@link
 *       com.example.invoice.service.domain.port.in.PartyRegistrationLookup}.</b> The
 *       inbound mapper resolves supplier / customer parties through the shared party-
 *       registration referential. See
 *       {@code EInvoiceFacadeMapper#enrichFromLookup} for the field mapping (name / mnemonic
 *       carry over; group / lei / internalCode have no equivalent yet).</li>
 *   <li><b>{@code SgDocV3Client} eliminated.</b> Outbound callers pass PDF / Excel bytes
 *       directly as {@link DocumentReferenceMapper.AttachmentPayload}. Fetching from a
 *       document store is the caller's concern; the mapper is a pure transformation.</li>
 *   <li><b>Spring {@code MultipartFile} eliminated from the module surface.</b>
 *       {@link MultipartExtractionService} returns {@link
 *       MultipartExtractionService.ExtractedAttachment} records
 *       ({@code filename / bytes / mimeType}); callers wrap into whatever their web layer
 *       expects. Required by the {@code invoice-mapper} enforcer rule, which bans
 *       {@code org.springframework:*}.</li>
 * </ul>
 *
 * <p><b>Sub-mapper graph.</b>
 * <pre>
 *   EInvoiceMappingService
 *     ├── EInvoiceFacadeMapper (constructor: PartyRegistrationLookup)
 *     │     ├── InvoiceTypeMapper (static)
 *     │     ├── AmountMapper      (static)
 *     │     ├── PartyMapper       (static)
 *     │     ├── LineItemMapper    (static)
 *     │     └── DocumentReferenceMapper (static; consumes AttachmentPayload)
 *     └── MultipartExtractionService  (plain class; no deps)
 * </pre>
 *
 * <p><b>Bringing up the facade.</b>
 * <pre>{@code
 *   PartyRegistrationLookup lookup = ...; // e.g. from invoice-service-cache
 *   var facade  = new EInvoiceFacadeMapper(lookup);
 *   var service = new EInvoiceMappingService(facade, new MultipartExtractionService());
 * }</pre>
 * Note that constructing the facade requires no Spring context and no database — the
 * enforcer rule on this module (see {@code invoice-mapper/pom.xml}) fails the build if
 * that ever changes.
 */
package com.example.invoice.mapper.einvoice;
