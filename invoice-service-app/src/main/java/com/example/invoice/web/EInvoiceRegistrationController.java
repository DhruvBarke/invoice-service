package com.example.invoice.web;

import com.example.invoice.config.EInvoiceJsonCodec;
import com.example.invoice.service.domain.einvoice.InvoiceRegistrationService;
import com.example.invoice.service.domain.einvoice.error.RegistrationOutcome;
import com.example.invoice.service.domain.model.invoice.ExtractedAttachment;
import com.example.invoice.service.domain.model.invoice.Invoice;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST entry point for e-invoice registration. Two ways in, one pipeline behind them.
 *
 * <p><b>{@code POST /invoices/einvoice}, {@code application/json}.</b> The e-invoice is the
 * request body and there are no uploads. Attachments, if any, are the ones embedded in the
 * document.
 *
 * <p><b>{@code POST /invoices/einvoice}, {@code multipart/form-data}.</b> The e-invoice is the
 * {@code invoice} part and files ride alongside it in {@code files}. Uploaded files win: when
 * any are present the copies embedded in the document are ignored rather than merged, because a
 * sender who uploads a corrected PDF while a superseded one is still embedded in the XML means
 * the upload. Merging would register both and leave a person to work out which one counts.
 *
 * <p>Both shapes are the same endpoint because they are the same operation. Splitting them into
 * {@code /einvoice} and {@code /einvoice/multipart} would make the sender's transport choice
 * look like a choice about what they are asking for.
 *
 * <p><b>No business logic here.</b> The controller deserialises, adapts Spring's
 * {@link MultipartFile} to the domain's {@link ExtractedAttachment}, and calls the use case. It
 * returns 200 whatever the outcome: a CANCELLED or INCOMPLETE registration is a stored, expected
 * result, not a transport failure, and a client that retried on a 4xx would keep resubmitting an
 * invoice that is already recorded. The status to act on is {@code outcome.status}.
 */
@RestController
@RequestMapping("/invoices")
public class EInvoiceRegistrationController {

  private final InvoiceRegistrationService registrationService;

  public EInvoiceRegistrationController(InvoiceRegistrationService registrationService) {
    this.registrationService = Objects.requireNonNull(registrationService, "registrationService");
  }

  /** JSON body, no uploads. Attachments come from inside the document. */
  @PostMapping(path = "/einvoice", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<RegistrationOutcome> registerJson(@RequestBody Invoice invoice) {
    return ResponseEntity.ok(registrationService.register(invoice, List.of()));
  }

  /**
   * Multipart: the document plus optional uploads.
   *
   * @param invoicePart the e-invoice, as JSON. Required.
   * @param files       uploaded attachments. Optional — absent or empty means fall back to
   *                    whatever the document itself carries.
   */
  @PostMapping(path = "/einvoice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<RegistrationOutcome> registerMultipart(
      @RequestPart("invoice") MultipartFile invoicePart,
      @RequestPart(value = "files", required = false) List<MultipartFile> files)
      throws IOException {

    Invoice invoice = EInvoiceJsonCodec.fromJson(
        new String(invoicePart.getBytes(), StandardCharsets.UTF_8));
    return ResponseEntity.ok(registrationService.register(invoice, toAttachments(files)));
  }

  /**
   * Adapt Spring's multipart type to the domain's.
   *
   * <p>Empty parts are dropped. Browsers and some HTTP clients send a zero-length part for a
   * file input that was left untouched, and treating that as an upload would suppress the
   * embedded-attachment fallback for a sender who uploaded nothing at all.
   */
  private static List<ExtractedAttachment> toAttachments(List<MultipartFile> files) {
    if (files == null) {
      return List.of();
    }
    List<ExtractedAttachment> out = new ArrayList<>(files.size());
    for (MultipartFile file : files) {
      if (file == null || file.isEmpty()) {
        continue;
      }
      try {
        out.add(new ExtractedAttachment(
            file.getOriginalFilename() != null ? file.getOriginalFilename() : file.getName(),
            file.getBytes(),
            file.getContentType() != null ? file.getContentType() : "application/octet-stream"));
      } catch (IOException e) {
        // One unreadable part must not sink the registration. The pipeline's attachment rules
        // decide what a missing file means for this business; failing the whole request here
        // would take that decision away from them and lose the invoice with it.
        throw new UnreadableUploadException(
            "could not read uploaded file '" + file.getOriginalFilename() + "'", e);
      }
    }
    return out;
  }

  /** Signals a multipart part that could not be read off the wire. */
  public static class UnreadableUploadException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UnreadableUploadException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
