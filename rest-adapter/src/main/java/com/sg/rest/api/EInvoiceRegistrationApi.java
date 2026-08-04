package com.sg.rest.api;

import com.sg.domaininterface.model.einvoice.error.RegistrationOutcome;
import com.sg.domaininterface.model.invoice.Invoice;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

/**
 * The HTTP contract for e-invoice registration.
 *
 * <p>The mappings live on the interface and the behaviour in the implementation, so the shape of
 * the API is readable in one short file. A change to a path or a media type is a change here,
 * which is the file a reviewer looks at when asking whether the API moved.
 *
 * <p><b>The e-invoice arrives as a model, never as a file.</b> Both shapes below bind it to
 * {@link Invoice} — the JSON one from the request body, the multipart one from a JSON part. It
 * used to come in as an uploaded {@code MultipartFile} that the controller read and parsed by
 * hand, which meant a malformed document surfaced as a parse exception from inside the
 * controller rather than as a 400 from the framework, and the API's own schema said "file"
 * where it meant "invoice".
 *
 * <p>Two shapes, one endpoint. A sender choosing multipart over JSON is choosing a transport
 * because they have files to attach, not asking for something different — splitting them into
 * two paths would make that look like two operations.
 *
 * <p><b>Both return 200 whatever the outcome.</b> A CANCELLED or INCOMPLETE registration is a
 * stored, expected result rather than a transport failure, and a client that retried on a 4xx
 * would resubmit an invoice that is already recorded. The status worth acting on is
 * {@code outcome.status}.
 */
@RequestMapping("/invoices")
public interface EInvoiceRegistrationApi {

  /**
   * Register an e-invoice with no uploads.
   *
   * <p>The document is the request body. Attachments, if any, are whichever ones it carries
   * embedded inside itself.
   */
  @PostMapping(path = "/einvoice",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<RegistrationOutcome> register(@RequestBody Invoice invoice);

  /**
   * Register an e-invoice with uploaded attachments.
   *
   * <p>The {@code invoice} part is the model, deserialised by the framework exactly as the JSON
   * body above — not a file to be read and parsed here.
   *
   * <p>Uploaded files win: when {@code files} carries anything, the copies embedded in the
   * document are ignored rather than merged. A sender who uploads a corrected PDF while a
   * superseded one is still embedded in the document means the upload, and registering both
   * would leave a person to work out which one counts.
   *
   * @param invoice the e-invoice model, as the {@code invoice} part. Required.
   * @param files   uploaded attachments. Optional — absent or empty falls back to the document's
   *                own.
   */
  @PostMapping(path = "/einvoice",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<RegistrationOutcome> registerWithAttachments(
      @RequestPart("invoice") Invoice invoice,
      @RequestPart(value = "files", required = false) List<MultipartFile> files);
}
