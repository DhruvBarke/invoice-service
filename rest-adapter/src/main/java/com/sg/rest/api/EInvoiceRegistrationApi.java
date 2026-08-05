package com.sg.rest.api;

import com.sg.domaininterface.model.einvoice.error.RegistrationOutcome;
import com.sg.domaininterface.model.invoice.Invoice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

/**
 * The HTTP contract for e-invoice registration.
 *
 * <p>The mappings and the OpenAPI description live on the interface; the behaviour lives in the
 * implementation. The shape of the API is therefore readable in one file, and a change to a path,
 * a media type or a documented response is a change here — the file a reviewer looks at when
 * asking whether the API moved.
 *
 * <p><b>The e-invoice arrives as a model, never as a file.</b> Both shapes below bind it to
 * {@link Invoice} — the JSON one from the request body, the multipart one from a JSON part. It
 * used to come in as an uploaded file that the controller read and parsed by hand, which meant a
 * malformed document surfaced as a parse exception from inside the controller rather than as a
 * 400 from the framework, and the API's own schema said "file" where it meant "invoice".
 *
 * <p>Two shapes, one endpoint. A sender choosing multipart over JSON is choosing a transport
 * because they have files to attach, not asking for something different — splitting them into two
 * paths would make that look like two operations.
 */
@Tag(
    name = "E-invoice registration",
    description = """
        Registers an inbound e-invoice as an invoice payable.

        **This endpoint answers 200 for business failures.** A refused, suspended or incomplete \
        registration is a stored, expected result, not a transport error. The row is written \
        either way and the verdict is in `status`; a client that treated a rejection as a 4xx \
        and retried would resubmit an invoice that is already recorded. Reserve retries for 5xx.

        Applies only to invoices arriving over e-invoicing. Manual capture and SGAi write the \
        same tables through a different path and never reach here.""")
@RequestMapping("/invoices")
public interface EInvoiceRegistrationApi {

  /** Reused across both operations so the two stay described identically. */
  String OUTCOME_DESCRIPTION = """
      The registration verdict. Always returned, including for failures.

      * `REGISTERED` — stored cleanly, no errors.
      * `CANCELLED` — refused or suspended. `lifecycleEvent` and `lifecycleReasonCode` say \
      which and why, and are echoed back to the sending platform.
      * `INCOMPLETE` — stored, but not usable as-is. The commonest cause is an invoice with no \
      line items, which a user completes in the application; no lifecycle event is emitted \
      because there is nothing for the sender to fix.

      `errors` lists every defect found, not just the first. An invoice with a malformed marker \
      AND an unresolvable fee type reports both, so the sender fixes them in one pass instead of \
      discovering the second on resubmission.""";

  @Operation(
      summary = "Register an e-invoice (no attachments)",
      operationId = "registerEInvoice",
      description = """
          The document is the request body. Any attachments are the ones it carries embedded \
          inside itself, as base64 in `additionalDocumentReference`.

          Use the multipart form of this endpoint instead if you have files to upload.""")
  @ApiResponses({
      @ApiResponse(
          responseCode = "200",
          description = OUTCOME_DESCRIPTION,
          content = @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = RegistrationOutcome.class),
              examples = {
                  @ExampleObject(
                      name = "Registered",
                      summary = "Stored cleanly",
                      value = """
                          {
                            "status": "REGISTERED",
                            "lifecycleEvent": null,
                            "lifecycleReasonCode": null,
                            "comment": null,
                            "errors": []
                          }"""),
                  @ExampleObject(
                      name = "Refused as a duplicate",
                      summary = "Already registered under the same supplier reference",
                      value = """
                          {
                            "status": "CANCELLED",
                            "lifecycleEvent": "REFUSED",
                            "lifecycleReasonCode": "DOUBLON",
                            "comment": "invoice SUP-INV-1 is already registered",
                            "errors": [
                              {
                                "code": "DUP-001",
                                "detail": "invoice SUP-INV-1 is already registered",
                                "detectedAt": "2026-08-05T09:15:00Z"
                              }
                            ]
                          }"""),
                  @ExampleObject(
                      name = "Incomplete",
                      summary = "Stored for a user to finish",
                      value = """
                          {
                            "status": "INCOMPLETE",
                            "lifecycleEvent": null,
                            "lifecycleReasonCode": null,
                            "comment": "no line items found for fee category CUSTODY",
                            "errors": [
                              {
                                "code": "LIN-001",
                                "detail": "no line items found for fee category CUSTODY",
                                "detectedAt": "2026-08-05T09:15:00Z"
                              }
                            ]
                          }""")
              })),
      @ApiResponse(
          responseCode = "400",
          description = """
              The body is not a readable e-invoice — malformed JSON, or a field whose type does \
              not match. Nothing is stored. Distinct from a 200 with a CANCELLED status, which \
              means the document was understood and rejected on its content.""",
          content = @Content),
      @ApiResponse(
          responseCode = "415",
          description = "Content-Type is neither application/json nor multipart/form-data.",
          content = @Content),
      @ApiResponse(
          responseCode = "500",
          description = """
              The registration could not be stored. The invoice was NOT recorded and the caller \
              should resend. This is the one class of response worth retrying.""",
          content = @Content)
  })
  @PostMapping(path = "/einvoice",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<RegistrationOutcome> register(
      @RequestBody(
          required = true,
          description = """
              The e-invoice, as a UBL-shaped model.

              The receiver's `accountingCustomerParty.party.endpointId.value` carries the routing \
              marker `<siren>_<BUSINESS>_<FEE_TYPE>` — for example `552120222_MARK_CUSTODY`. The \
              business selects which validation rules run and where alerts go; the fee type is \
              resolved against the fee referential. A marker that cannot be read is recorded and \
              refused, not silently defaulted.

              `id` is the supplier's own reference for the invoice. It is stored as \
              `providerReference` and is what the duplicate check keys on; SG mints its own \
              `invoiceReference` separately, because a supplier's id is only unique within that \
              supplier.""",
          content = @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = Invoice.class),
              examples = @ExampleObject(
                  name = "Custody invoice",
                  value = """
                      {
                        "id": "SUP-INV-1",
                        "issueDate": "2026-01-15",
                        "invoiceTypeCode": "380",
                        "documentCurrencyCode": "EUR",
                        "accountingSupplierParty": {
                          "party": {
                            "partyLegalEntity": { "companyId": { "value": "552120222" } }
                          }
                        },
                        "accountingCustomerParty": {
                          "party": {
                            "endpointId": { "value": "552120222_MARK_CUSTODY" }
                          }
                        },
                        "invoiceLine": [
                          {
                            "id": "1",
                            "item": { "name": "CUSTODY FEE" },
                            "lineExtensionAmount": { "value": 50.00, "currencyID": "EUR" }
                          }
                        ]
                      }""")))
      @org.springframework.web.bind.annotation.RequestBody Invoice invoice);

  @Operation(
      summary = "Register an e-invoice with uploaded attachments",
      operationId = "registerEInvoiceWithAttachments",
      description = """
          The `invoice` part is the model, deserialised by the framework exactly as the JSON body \
          on the other form — not a file to be read and parsed.

          **Uploaded files win.** When `files` carries anything, the copies embedded in the \
          document are ignored rather than merged. A sender who uploads a corrected PDF while a \
          superseded one is still embedded in the XML means the upload; registering both would \
          leave a person to work out which one counts. Send no files, or an empty part, to fall \
          back to whatever the document carries.

          Zero-length parts are dropped rather than counted as uploads — several HTTP clients \
          send one for a file input the user left untouched, and treating it as an upload would \
          suppress the embedded-attachment fallback for a sender who attached nothing at all.""")
  @ApiResponses({
      @ApiResponse(
          responseCode = "200",
          description = OUTCOME_DESCRIPTION,
          content = @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = RegistrationOutcome.class))),
      @ApiResponse(
          responseCode = "400",
          description = """
              The `invoice` part is missing or unreadable, or the request could not be parsed \
              as multipart at all. Returns an RFC 7807 problem document. Nothing is stored, \
              and resending unchanged will fail identically.""",
          content = @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(implementation = ProblemDetail.class))),
      @ApiResponse(
          responseCode = "413",
          description = """
              An uploaded file exceeded the configured limit — 10MB per file and 25MB per \
              request by default. The problem document carries `maxUploadSizeBytes` so the \
              sender knows what to aim at. Nothing is stored. **Do not retry**: the same file \
              is rejected the same way. Split the attachment, or ask for the limit to be \
              raised.""",
          content = @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(implementation = ProblemDetail.class),
              examples = @ExampleObject(value = """
                  {
                    "type": "about:blank",
                    "title": "Upload too large",
                    "status": 413,
                    "detail": "An uploaded file exceeded the configured limit.",
                    "maxUploadSizeBytes": 10485760
                  }"""))),
      @ApiResponse(
          responseCode = "415",
          description = "Content-Type is not multipart/form-data.",
          content = @Content),
      @ApiResponse(
          responseCode = "500",
          description = """
              The registration could not be stored, or an uploaded part could not be read off \
              the wire. The invoice was NOT recorded; resend.""",
          content = @Content)
  })
  @PostMapping(path = "/einvoice",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<RegistrationOutcome> registerWithAttachments(
      @Parameter(
          name = "invoice",
          required = true,
          description = "The e-invoice model, as a JSON part. Same schema as the JSON body form.",
          content = @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = Invoice.class)))
      @RequestPart("invoice") Invoice invoice,

      @Parameter(
          name = "files",
          description = """
              Attachments. Optional — absent or empty falls back to the document's own.

              A `.pdf` is recorded as the invoice document; a `.csv`, `.xlsx` or `.xls` is \
              recorded as a trade file, which brokerage fee types require. Anything else is \
              stored as OTHER. Content is uploaded to the document store and only the returned \
              handle is kept on the invoice row.""",
          content = @Content(
              mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
              schema = @Schema(type = "array", format = "binary")))
      @RequestPart(value = "files", required = false) List<MultipartFile> files);
}
