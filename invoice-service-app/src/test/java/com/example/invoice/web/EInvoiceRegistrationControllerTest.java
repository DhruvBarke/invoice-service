package com.example.invoice.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.invoice.service.domain.einvoice.Business;
import com.example.invoice.service.domain.einvoice.EInvoiceMarker;
import com.example.invoice.service.domain.einvoice.InvoiceRegistrationService;
import com.example.invoice.service.domain.einvoice.error.RegistrationOutcome;
import com.example.invoice.service.domain.einvoice.port.EInvoiceMappingPort;
import com.example.invoice.service.domain.einvoice.port.EInvoiceMappingPort.MappingResult;
import com.example.invoice.service.domain.einvoice.port.InvoicePayableStore;
import com.example.invoice.service.domain.einvoice.port.LifecycleEventPublisher;
import com.example.invoice.service.domain.einvoice.port.RegistrationAlertNotifier;
import com.example.invoice.service.domain.einvoice.rule.AttachmentChannel;
import com.example.invoice.service.domain.einvoice.rule.ValidationRegistry;
import com.example.invoice.service.domain.model.invoice.ExtractedAttachment;
import com.example.invoice.service.domain.model.invoice.Invoice;
import com.example.invoice.service.domain.model.payableinvoice.InvoiceDocumentPayable;
import com.example.invoice.service.domain.model.payableinvoice.InvoicePayable;
import com.example.invoice.service.domain.model.payableinvoice.InvoicePayableModel;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/**
 * The two ways into the pipeline, and the choice the controller makes between them.
 *
 * <p>Driven against a real {@link InvoiceRegistrationService} with stub ports rather than a
 * mocked service, so what the tests assert is what the pipeline actually received — a mocked
 * service would let the controller pass it anything and still look correct.
 */
class EInvoiceRegistrationControllerTest {

  private static final String INVOICE_JSON = """
      {"id":"SUP-INV-1","accountingCustomerParty":{"party":{"endpointId":{"value":"552120222_MARK_CUSTODY"}}}}
      """;

  /** Captures what the pipeline was handed. */
  private static final class CapturingStore implements InvoicePayableStore {
    final AtomicReference<PersistRequest> last = new AtomicReference<>();

    @Override public UUID persist(PersistRequest request) {
      last.set(request);
      return UUID.randomUUID();
    }
  }

  private record Fixture(EInvoiceRegistrationController controller, CapturingStore store) {}

  /** A pipeline whose mapping reports the given embedded attachments and nothing wrong. */
  private static Fixture fixture(List<ExtractedAttachment> embedded) {
    InvoicePayableModel model = new InvoicePayableModel();
    model.setInvoicePayable(new InvoicePayable());

    EInvoiceMappingPort port = inv -> new MappingResult(
        model, List.of(), embedded,
        new EInvoiceMarker("552120222", Business.MARK, "CUSTODY", "552120222_MARK_CUSTODY"),
        "F01", "CUSTODY", List.of());

    CapturingStore store = new CapturingStore();
    InvoiceRegistrationService service = new InvoiceRegistrationService(
        port, ValidationRegistry.builder().build(), store,
        (LifecycleEventPublisher) e -> { },
        (RegistrationAlertNotifier) a -> { });
    return new Fixture(new EInvoiceRegistrationController(service), store);
  }

  /**
   * Minimal {@link MultipartFile}; the real one drags in a servlet container.
   *
   * <p>A class rather than a record so the unreadable-part case can subclass it, and
   * {@code getBytes} declares {@code IOException} so that subclass can throw one — which is the
   * whole failure being tested.
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

  private static StubFile upload(String filename) {
    return new StubFile("files", filename, new byte[] {1, 2, 3}, "application/pdf");
  }

  private static List<InvoiceDocumentPayable> documents(Fixture f) {
    return f.store().last.get().documents();
  }

  // ── JSON body ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("a JSON body registers, using whatever the document itself carried")
  void jsonBodyUsesEmbedded() {
    Fixture f = fixture(List.of(
        new ExtractedAttachment("embedded.pdf", new byte[] {9}, "application/pdf")));

    Invoice invoice = new Invoice();
    invoice.setId("SUP-INV-1");
    ResponseEntity<RegistrationOutcome> response = f.controller().registerJson(invoice);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(1, documents(f).size());
    assertEquals(AttachmentChannel.EINVOICE_BODY.name(), documents(f).get(0).getIncomingLine(),
        "there is no upload channel on a JSON request, so the document's own copy is used");
  }

  // ── Multipart ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("uploaded files supersede the copies embedded in the document")
  void uploadWins() throws IOException {
    Fixture f = fixture(List.of(
        new ExtractedAttachment("embedded.pdf", new byte[] {9}, "application/pdf")));

    ResponseEntity<RegistrationOutcome> response =
        f.controller().registerMultipart(json(), List.of(upload("uploaded.pdf")));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(1, documents(f).size(), "not merged with the embedded copy");
    assertEquals("uploaded.pdf", documents(f).get(0).getDocumentName());
    assertEquals(AttachmentChannel.MULTIPART.name(), documents(f).get(0).getIncomingLine());
  }

  @Test
  @DisplayName("multipart with no files falls back to the document's own attachments")
  void absentFilesFallBack() throws IOException {
    Fixture f = fixture(List.of(
        new ExtractedAttachment("embedded.pdf", new byte[] {9}, "application/pdf")));

    f.controller().registerMultipart(json(), null);

    assertEquals(1, documents(f).size());
    assertEquals(AttachmentChannel.EINVOICE_BODY.name(), documents(f).get(0).getIncomingLine());
  }

  @Test
  @DisplayName("an empty file part is not an upload")
  void emptyPartIsIgnored() throws IOException {
    Fixture f = fixture(List.of(
        new ExtractedAttachment("embedded.pdf", new byte[] {9}, "application/pdf")));

    // A file input left untouched arrives as a zero-length part in several clients. Counting it
    // as an upload would suppress the embedded fallback for a sender who attached nothing.
    f.controller().registerMultipart(json(), java.util.Arrays.asList(
        new StubFile("files", "", new byte[0], "application/octet-stream"), null));

    assertEquals(1, documents(f).size());
    assertEquals(AttachmentChannel.EINVOICE_BODY.name(), documents(f).get(0).getIncomingLine(),
        "an empty part must not shadow the document's own copy");
  }

  @Test
  @DisplayName("a part with no original filename falls back to the part name")
  void missingOriginalFilename() throws IOException {
    Fixture f = fixture(List.of());

    f.controller().registerMultipart(json(),
        List.of(new StubFile("files", null, new byte[] {1}, null)));

    assertEquals("files", documents(f).get(0).getDocumentName());
    assertEquals("application/octet-stream", documents(f).get(0).getFormat(),
        "an undeclared content type is recorded as the generic one rather than as null");
  }

  @Test
  @DisplayName("an unreadable part fails loudly rather than silently registering without it")
  void unreadablePart() {
    Fixture f = fixture(List.of());
    MultipartFile broken = new StubFile("files", "broken.pdf", new byte[] {1}, "application/pdf") {
      @Override public byte[] getBytes() throws IOException {
        throw new IOException("stream died");
      }
    };

    // Registering as though nothing was attached would hand the attachment rules a false
    // premise and refuse the invoice for a fault on our side of the wire.
    assertThrows(EInvoiceRegistrationController.UnreadableUploadException.class,
        () -> f.controller().registerMultipart(json(), List.of(broken)));
  }

  // ── Guards ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("the service is mandatory")
  void serviceMandatory() {
    assertThrows(NullPointerException.class, () -> new EInvoiceRegistrationController(null));
  }

  @Test
  @DisplayName("a failed registration is still HTTP 200 with the reason in the body")
  void failuresAreNotTransportErrors() {
    // A client that retried on a 4xx would resubmit an invoice that is already recorded.
    EInvoiceMappingPort refusing = inv -> new MappingResult(
        null, List.of(), List.of(), EInvoiceMarker.empty(), null, null,
        List.of(com.example.invoice.service.domain.einvoice.error.MappingError.of(
            com.example.invoice.service.domain.einvoice.error.ErrorCode.BUSINESS_UNKNOWN,
            "no business token")));

    EInvoiceRegistrationController controller = new EInvoiceRegistrationController(
        new InvoiceRegistrationService(refusing, ValidationRegistry.builder().build(),
            new CapturingStore(), e -> { }, a -> { }));

    Invoice invoice = new Invoice();
    invoice.setId("SUP-INV-1");
    ResponseEntity<RegistrationOutcome> response = controller.registerJson(invoice);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertTrue(response.getBody().hasErrors(), "the reason travels in the body, not the status");
  }
}
