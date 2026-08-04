package com.sg.rest.api;

import com.sg.domaininterface.model.einvoice.error.RegistrationOutcome;
import com.sg.domaininterface.model.invoice.Invoice;
import java.io.IOException;
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
 * <p>The mappings live on the interface and the behaviour lives in the implementation, so the
 * shape of the API is readable in one short file without the adaptation code around it. It also
 * means a change to a path or a media type is a change to this file, which is the one a reviewer
 * will actually look at when asking whether the API moved.
 *
 * <p>Two ways in, one operation. Both are the same endpoint because a sender choosing multipart
 * over JSON is choosing a transport, not asking for something different — splitting them into
 * {@code /einvoice} and {@code /einvoice/multipart} would make that look like a different
 * request.
 *
 * <p><b>Both return 200 whatever the outcome.</b> A CANCELLED or INCOMPLETE registration is a
 * stored, expected result rather than a transport failure, and a client that retried on a 4xx
 * would resubmit an invoice that is already recorded. The status worth acting on is
 * {@code outcome.status}.
 */
@RequestMapping("/invoices")
public interface EInvoiceRegistrationApi {

  /**
   * Register an e-invoice sent as a JSON body.
   *
   * <p>There is no upload channel on this shape, so the attachments are whichever ones the
   * document carries inside itself.
   */
  @PostMapping(path = "/einvoice", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<RegistrationOutcome> registerJson(@RequestBody Invoice invoice);

  /**
   * Register an e-invoice sent as multipart, with optional file uploads.
   *
   * <p>Uploaded files win: when {@code files} carries anything, the copies embedded in the
   * document are ignored rather than merged. A sender who uploads a corrected PDF while a
   * superseded one is still embedded in the document means the upload, and registering both
   * would leave a person to work out which one counts.
   *
   * @param invoicePart the e-invoice as JSON. Required.
   * @param files       uploaded attachments. Optional — absent or empty falls back to the
   *                    document's own.
   */
  @PostMapping(path = "/einvoice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<RegistrationOutcome> registerMultipart(
      @RequestPart("invoice") MultipartFile invoicePart,
      @RequestPart(value = "files", required = false) List<MultipartFile> files)
      throws IOException;
}
