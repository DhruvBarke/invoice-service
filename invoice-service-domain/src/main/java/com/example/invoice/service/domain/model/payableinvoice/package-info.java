/**
 * Invoice-service payable payload types consumed by the einvoice mappers.
 *
 * <p><b>Vendored from A's {@code com.sg.domaininterface.model.payableinvoice} package</b>
 * (see {@code invoice-service-mapping/} in the source einvoice-service repo). Two design
 * choices that differ from the source:
 *
 * <ul>
 *   <li>Jackson annotations stripped ({@code @JsonIgnoreProperties}, {@code @JsonDeserialize},
 *       {@code @JsonSerialize}). This module has no runtime Jackson dependency; JSON binding
 *       is the caller's concern.</li>
 *   <li>{@link InvoicePayableModel} and {@link InvoiceItem} are hand-reconstructed rather than
 *       vendored — the source classes lived in the upstream invoice-service host and were not
 *       in A's tree. Fields cover exactly the getter/setter surface the einvoice mappers
 *       touch, no more.</li>
 * </ul>
 */
package com.example.invoice.service.domain.model.payableinvoice;
