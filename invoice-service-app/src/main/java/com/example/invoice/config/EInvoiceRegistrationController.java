package com.example.invoice.config;

import com.example.invoice.mapper.einvoice.MultipartExtractionService.ExtractedAttachment;
import com.example.invoice.mapper.einvoice.model.invoice.Invoice;
import com.example.invoice.service.registration.InvoiceRegistrationService;
import com.example.invoice.service.registration.error.RegistrationOutcome;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Thin REST layer for the e-invoice registration pipeline.
 *
 * <p>Accepts a multipart request with:
 * <ul>
 *   <li><b>{@code invoice}</b> — JSON body of the {@link Invoice} to register (required).</li>
 *   <li><b>{@code attachments}</b> — zero or more file parts (PDF, XLSX, CSV, ...) supplied
 *       alongside the JSON. Optional at the transport level; the pipeline's
 *       {@code AttachmentPresentRule} decides whether their absence is a failure.</li>
 * </ul>
 *
 * <p>The controller does no business logic — it deserialises, adapts Spring
 * {@link MultipartFile} to {@link ExtractedAttachment}, calls
 * {@link InvoiceRegistrationService#register(Invoice, java.util.List)}, and echoes the
 * {@link RegistrationOutcome} as JSON. HTTP status is 200 regardless of the outcome status:
 * a CANCELLED or INCOMPLETE registration is a stored, expected outcome, not a transport
 * failure. The client reads {@code outcome.status} to distinguish.
 */
@RestController
@RequestMapping("/invoices")
public class EInvoiceRegistrationController {

  private final InvoiceRegistrationService registrationService;

  public EInvoiceRegistrationController(InvoiceRegistrationService registrationService) {
    this.registrationService = Objects.requireNonNull(registrationService, "registrationService");
  }

  @PostMapping(path = "/einvoice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<RegistrationOutcome> registerEInvoice(
      @RequestPart("invoice") MultipartFile invoicePart,
      @RequestPart(value = "attachments", required = false) List<MultipartFile> attachments)
      throws IOException {

    Invoice invoice = EInvoiceJsonCodec.fromJson(
        new String(invoicePart.getBytes(), java.nio.charset.StandardCharsets.UTF_8));

    List<ExtractedAttachment> multipart = new ArrayList<>();
    if (attachments != null) {
      for (MultipartFile file : attachments) {
        if (file == null || file.isEmpty()) continue;
        multipart.add(new ExtractedAttachment(
            file.getOriginalFilename() != null ? file.getOriginalFilename() : file.getName(),
            file.getBytes(),
            file.getContentType() != null ? file.getContentType() : "application/octet-stream"));
      }
    }

    RegistrationOutcome outcome = registrationService.register(invoice, multipart);
    return ResponseEntity.status(HttpStatus.OK).body(outcome);
  }
}
