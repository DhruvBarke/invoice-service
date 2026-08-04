package com.sg.mapper.einvoice;

import com.sg.mapper.einvoice.DocumentReferenceMapper.AttachmentPayload;
import com.sg.domaininterface.model.invoice.ExtractedAttachment;
import com.sg.domaininterface.model.invoice.Invoice;
import com.sg.domaininterface.model.payableinvoice.InvoiceItem;
import com.sg.domaininterface.model.payableinvoice.InvoicePayableModel;
import java.util.List;
import java.util.Objects;

/**
 * Public facade the invoice-service team depends on. Hides the mapper graph behind a stable
 * three-method surface so the sub-mapping graph can be refactored without breaking callers.
 *
 * <p>Ported from A's {@code EInvoiceMappingService}. Two shape changes vs A:
 *
 * <ul>
 *   <li><b>{@code SgDocV3Client} eliminated.</b> A's outbound method took just
 *       {@code (model, items)} and internally used the injected {@code sgdoc} to fetch
 *       attachment bytes. Callers now pass the {@link AttachmentPayload}s directly; the
 *       facade is a pure transformation.</li>
 *   <li><b>{@code extractAttachments} returns {@code List<ExtractedAttachment>}</b> instead
 *       of Spring {@code MultipartFile[]}. The mapper module has no Spring dependency (see
 *       {@link MultipartExtractionService}); the caller wraps into their web layer's type.</li>
 * </ul>
 *
 * <p>Typical inbound call sequence:
 * <pre>{@code
 *   var result = service.toInvoicePayable(einvoice);
 *   var files = service.extractAttachments(einvoice);
 *   invoicePayableRegistrationService.register(result.model(), result.items(), files);
 * }</pre>
 *
 * <p>Typical outbound call sequence:
 * <pre>{@code
 *   var pdf = new AttachmentPayload(model.getInvoicePayable().getInvoicePdfId(),
 *                                   docStore.fetchBytes(pdfId), "invoice.pdf", "application/pdf");
 *   var xlsx = ...;
 *   Invoice einvoice = service.toEInvoice(model, items, pdf, xlsx);
 * }</pre>
 */
public class EInvoiceMappingService {

  private final EInvoiceFacadeMapper facade;
  private final MultipartExtractionService multipartExtractor;

  public EInvoiceMappingService(
      EInvoiceFacadeMapper facade, MultipartExtractionService multipartExtractor) {
    this.facade = Objects.requireNonNull(facade, "facade");
    this.multipartExtractor = Objects.requireNonNull(multipartExtractor, "multipartExtractor");
  }

  /**
   * Outbound: {@link InvoicePayableModel} + lines → einvoice {@link Invoice}. Callers pass
   * attachment bytes as {@link AttachmentPayload}s; pass {@code null} for either slot when
   * absent.
   */
  public Invoice toEInvoice(
      InvoicePayableModel model,
      List<InvoiceItem> items,
      AttachmentPayload pdf,
      AttachmentPayload excel) {
    return facade.toEInvoice(model, items, pdf, excel);
  }

  /**
   * Inbound: einvoice {@link Invoice} → {@link InvoicePayableModel} + lines. Does not save
   * attachments anywhere; use {@link #extractAttachments(Invoice)} for the file payloads.
   */
  public EInvoiceFacadeMapper.MappedResult toInvoicePayable(Invoice eInvoice) {
    return facade.toInvoicePayable(eInvoice);
  }

  /**
   * Inbound companion: pull the base64 attachments out of the einvoice as raw byte payloads.
   * Corrupt or empty attachments are silently dropped; call
   * {@link MultipartExtractionService#extractDetailed} directly if the caller needs
   * per-attachment status.
   */
  public List<ExtractedAttachment> extractAttachments(Invoice eInvoice) {
    return multipartExtractor.extract(eInvoice);
  }
}
