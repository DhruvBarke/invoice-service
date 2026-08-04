package com.sg.rest.controller;

import com.sg.domaininterface.model.einvoice.error.RegistrationOutcome;
import com.sg.domaininterface.model.invoice.ExtractedAttachment;
import com.sg.domaininterface.model.invoice.Invoice;
import com.sg.domaininterface.service.InvoiceRegistrationService;
import com.sg.rest.api.EInvoiceRegistrationApi;
import com.sg.rest.codec.EInvoiceJsonCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Implements {@link EInvoiceRegistrationApi}. The paths and media types are on the interface;
 * what is here is the adaptation.
 *
 * <p><b>No business logic.</b> This deserialises, converts Spring's {@link MultipartFile} to the
 * domain's {@link ExtractedAttachment}, and calls the service interface. It does not decide
 * whether a missing attachment matters — the pipeline's rules do, per business, and a controller
 * making that call would put the transport layer in charge of policy.
 */
@RestController
public class EInvoiceRegistrationController implements EInvoiceRegistrationApi {

  private final InvoiceRegistrationService registrationService;

  public EInvoiceRegistrationController(InvoiceRegistrationService registrationService) {
    this.registrationService = Objects.requireNonNull(registrationService, "registrationService");
  }

  @Override
  public ResponseEntity<RegistrationOutcome> registerJson(Invoice invoice) {
    return ResponseEntity.ok(registrationService.register(invoice, List.of()));
  }

  @Override
  public ResponseEntity<RegistrationOutcome> registerMultipart(
      MultipartFile invoicePart, List<MultipartFile> files) throws IOException {

    Invoice invoice = EInvoiceJsonCodec.fromJson(
        new String(invoicePart.getBytes(), StandardCharsets.UTF_8));
    return ResponseEntity.ok(registrationService.register(invoice, toAttachments(files)));
  }

  /**
   * Adapt Spring's multipart type to the domain's.
   *
   * <p>Empty parts are dropped. Browsers and several HTTP clients send a zero-length part for a
   * file input that was left untouched, and treating that as an upload would suppress the
   * embedded-attachment fallback for a sender who attached nothing at all.
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
        // Registering as though nothing was attached would hand the attachment rules a false
        // premise and refuse the invoice for a fault on our side of the wire.
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
