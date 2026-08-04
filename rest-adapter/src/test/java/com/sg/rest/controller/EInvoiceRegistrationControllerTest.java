package com.sg.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domaininterface.model.einvoice.error.ErrorCode;
import com.sg.domaininterface.model.einvoice.error.MappingError;
import com.sg.domaininterface.model.einvoice.error.RegistrationOutcome;
import com.sg.domaininterface.model.invoice.ExtractedAttachment;
import com.sg.domaininterface.model.invoice.Invoice;
import com.sg.domaininterface.service.InvoiceRegistrationService;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/**
 * The two ways into the API, and what the controller hands the service for each.
 *
 * <p>Driven against a stub {@link InvoiceRegistrationService}, not a real one. That is not a
 * shortcut: rest-adapter may depend on domain-interface only, so the implementation is not on
 * this module's classpath and could not be constructed here even if it were wanted. What remains
 * is exactly what this class is responsible for — deserialising, adapting Spring's multipart
 * type, and deciding which attachments to forward.
 */
class EInvoiceRegistrationControllerTest {

  private static final String INVOICE_JSON =
      "{\"id\":\"SUP-INV-1\",\"accountingCustomerParty\":{\"party\":"
          + "{\"endpointId\":{\"value\":\"552120222_MARK_CUSTODY\"}}}}";

  /** Records what the controller forwarded. */
  private static final class RecordingService implements InvoiceRegistrationService {
    final AtomicReference<Invoice> invoice = new AtomicReference<>();
    final AtomicReference<List<ExtractedAttachment>> attachments = new AtomicReference<>();
    RegistrationOutcome answer = RegistrationOutcome.decide(List.of());

    @Override
    public RegistrationOutcome register(Invoice inv, List<ExtractedAttachment> uploaded) {
      invoice.set(inv);
      attachments.set(uploaded);
      return answer;
    }
  }

  private record Fixture(EInvoiceRegistrationController controller, RecordingService service) {}

  private static Fixture fixture() {
    RecordingService service = new RecordingService();
    return new Fixture(new EInvoiceRegistrationController(service), service);
  }

  /**
   * Minimal {@link MultipartFile}; the real one drags in a servlet container.
   *
   * <p>A class rather than a record so the unreadable-part case can subclass it, and
   * {@code getBytes} declares {@code IOException} so that subclass can throw one — which is the
   * failure being tested.
   */
  private static class StubFile implements MultipartFile {
    private final String name;
    private final String filename;
    private final byte[] content;
    private final String contentType;

    StubFile(String name, String filename, byte[] content, String contentType) {
      this.name = name;
      this.filename = filename;
      this.content = content;
      this.contentType = contentType;
    }

    @Override public String getName() { return name; }
    @Override public String getOriginalFilename() { return filename; }
    @Override public String getContentType() { return contentType; }
    @Override public boolean isEmpty() { return content == null || content.length == 0; }
    @Override public long getSize() { return content == null ? 0 : content.length; }
    @Override public byte[] getBytes() throws IOException { return content; }
    @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
    @Override public void transferTo(File dest) {
      throw new UnsupportedOperationException("not needed");
    }
  }

  private static StubFile json() {
    return new StubFile("invoice", "invoice.json",
        INVOICE_JSON.getBytes(StandardCharsets.UTF_8), "application/json");
  }

  // ── JSON body ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("a JSON body is forwarded as-is, with no uploads")
  void jsonBodyForwardsNoUploads() {
    Fixture f = fixture();
    Invoice invoice = new Invoice();
    invoice.setId("SUP-INV-1");

    ResponseEntity<RegistrationOutcome> response = f.controller().registerJson(invoice);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertSame(invoice, f.service().invoice.get(), "no copying, no re-parsing");
    assertTrue(f.service().attachments.get().isEmpty(),
        "there is no upload channel on this shape, so the service falls back to whatever the "
            + "document carries itself");
  }

  // ── Multipart ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("the invoice part is parsed and the files are forwarded")
  void multipartForwardsUploads() throws IOException {
    Fixture f = fixture();

    ResponseEntity<RegistrationOutcome> response = f.controller().registerMultipart(
        json(), List.of(new StubFile("files", "trades.csv", new byte[] {1, 2}, "text/csv")));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("SUP-INV-1", f.service().invoice.get().getId());

    List<ExtractedAttachment> forwarded = f.service().attachments.get();
    assertEquals(1, forwarded.size());
    assertEquals("trades.csv", forwarded.get(0).filename());
    assertEquals("text/csv", forwarded.get(0).mimeType());
    assertEquals(2, forwarded.get(0).bytes().length);
  }

  @Test
  @DisplayName("absent files forward as empty, which is what triggers the fallback")
  void absentFilesForwardEmpty() throws IOException {
    Fixture f = fixture();
    f.controller().registerMultipart(json(), null);
    assertTrue(f.service().attachments.get().isEmpty());
  }

  @Test
  @DisplayName("empty and null parts are dropped rather than forwarded")
  void emptyPartsAreDropped() throws IOException {
    Fixture f = fixture();

    // Several clients send a zero-length part for a file input left untouched. Forwarding it
    // would count as an upload and suppress the embedded-attachment fallback for a sender who
    // attached nothing at all.
    f.controller().registerMultipart(json(), Arrays.asList(
        new StubFile("files", "", new byte[0], "application/octet-stream"), null));

    assertTrue(f.service().attachments.get().isEmpty(),
        "an empty part must not read as an upload");
  }

  @Test
  @DisplayName("a part with no original filename or content type gets usable defaults")
  void missingPartMetadata() throws IOException {
    Fixture f = fixture();

    f.controller().registerMultipart(json(),
        List.of(new StubFile("files", null, new byte[] {1}, null)));

    ExtractedAttachment a = f.service().attachments.get().get(0);
    assertEquals("files", a.filename(), "falls back to the part name");
    assertEquals("application/octet-stream", a.mimeType(),
        "an undeclared content type is recorded as the generic one rather than as null");
  }

  @Test
  @DisplayName("an unreadable part fails loudly rather than registering without it")
  void unreadablePart() {
    Fixture f = fixture();
    MultipartFile broken =
        new StubFile("files", "broken.pdf", new byte[] {1}, "application/pdf") {
          @Override public byte[] getBytes() throws IOException {
            throw new IOException("stream died");
          }
        };

    // Forwarding as though nothing was attached would hand the attachment rules a false premise
    // and refuse the invoice for a fault on our side of the wire.
    assertThrows(EInvoiceRegistrationController.UnreadableUploadException.class,
        () -> f.controller().registerMultipart(json(), List.of(broken)));
  }

  // ── Contract ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("the service is mandatory")
  void serviceMandatory() {
    assertThrows(NullPointerException.class, () -> new EInvoiceRegistrationController(null));
  }

  @Test
  @DisplayName("a failed registration is still HTTP 200, with the reason in the body")
  void failuresAreNotTransportErrors() {
    Fixture f = fixture();
    f.service().answer = RegistrationOutcome.decide(new ArrayList<>(List.of(
        MappingError.of(ErrorCode.BUSINESS_UNKNOWN, "no business token"))));

    Invoice invoice = new Invoice();
    invoice.setId("SUP-INV-1");
    ResponseEntity<RegistrationOutcome> response = f.controller().registerJson(invoice);

    // A client that retried on a 4xx would resubmit an invoice that is already recorded.
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertTrue(response.getBody().hasErrors(), "the reason travels in the body, not the status");
  }
}
