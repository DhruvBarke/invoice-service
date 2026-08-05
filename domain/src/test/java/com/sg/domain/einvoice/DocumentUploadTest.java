package com.sg.domain.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domain.einvoice.rule.ValidationRegistry;
import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.model.einvoice.EInvoiceMarker;
import com.sg.domaininterface.model.einvoice.error.ErrorCode;
import com.sg.domaininterface.model.einvoice.error.MappingError;
import com.sg.domaininterface.model.einvoice.error.RegistrationOutcome;
import com.sg.domaininterface.model.invoice.ExtractedAttachment;
import com.sg.domaininterface.model.invoice.Invoice;
import com.sg.domaininterface.model.payableinvoice.InvoiceDocumentPayable;
import com.sg.domaininterface.model.payableinvoice.InvoicePayable;
import com.sg.domaininterface.model.payableinvoice.InvoicePayableModel;
import com.sg.domaininterface.port.out.EInvoiceMappingPort;
import com.sg.domaininterface.port.out.EInvoiceMappingPort.MappingResult;
import com.sg.domaininterface.port.out.InvoicePayableStore;
import com.sg.domaininterface.port.out.LifecycleEventPublisher;
import com.sg.domaininterface.port.out.RegistrationAlertNotifier;
import com.sg.domaininterface.port.thirdparty.ReferentialUnavailableException;
import com.sg.domaininterface.port.thirdparty.SgDocReferentialService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Attachments reach the document store, and what happens when they cannot.
 *
 * <p>Without the upload step every document row carried a null handle forever and the bytes were
 * dropped on the floor — while the row still read as though a document had been received. The
 * handle is the only route back to the content, so its presence or absence is the whole contract
 * here.
 */
class DocumentUploadTest {

  private static final ExtractedAttachment PDF =
      new ExtractedAttachment("invoice.pdf", new byte[] {1, 2, 3}, "application/pdf");
  private static final ExtractedAttachment CSV =
      new ExtractedAttachment("trades.csv", new byte[] {4, 5}, "text/csv");

  /** Captures the request so the document rows can be inspected. */
  private static final class CapturingStore implements InvoicePayableStore {
    PersistRequest last;

    @Override
    public UUID persist(PersistRequest request) {
      last = request;
      return UUID.randomUUID();
    }
  }

  private record Uploaded(String filename, String invoiceReference) {}

  private static final class RecordingDocs implements SgDocReferentialService {
    final List<Uploaded> calls = new ArrayList<>();
    private final AtomicInteger next = new AtomicInteger();

    @Override
    public String upload(ExtractedAttachment attachment, String invoiceReference) {
      calls.add(new Uploaded(attachment.filename(), invoiceReference));
      return "DOC-" + next.incrementAndGet();
    }

    @Override
    public ExtractedAttachment download(String sgDocId) {
      throw new UnsupportedOperationException("registration never reads a document back");
    }
  }

  private static EInvoiceMappingPort port(List<ExtractedAttachment> embedded) {
    InvoicePayableModel model = new InvoicePayableModel();
    model.setInvoicePayable(new InvoicePayable());
    return inv -> new MappingResult(model, List.of(), embedded,
        new EInvoiceMarker("552120222", Business.MARK, "CUSTODY", "552120222_MARK_CUSTODY"),
        "F01", "CUSTODY", List.of());
  }

  private static Invoice invoice() {
    Invoice invoice = new Invoice();
    invoice.setId("SUP-INV-1");
    return invoice;
  }

  private static InvoiceRegistrationServiceImpl service(SgDocReferentialService docs,
                                                        CapturingStore store,
                                                        List<ExtractedAttachment> embedded) {
    return new InvoiceRegistrationServiceImpl(
        port(embedded), docs, ValidationRegistry.builder().build(), store,
        (LifecycleEventPublisher) e -> { }, (RegistrationAlertNotifier) a -> { });
  }

  // ── The happy path ────────────────────────────────────────────────────────

  @Test
  @DisplayName("uploaded files are stored and their handles land on the rows")
  void uploadedFilesGetHandles() {
    RecordingDocs docs = new RecordingDocs();
    CapturingStore store = new CapturingStore();

    service(docs, store, List.of()).register(invoice(), List.of(PDF, CSV));

    assertEquals(List.of("invoice.pdf", "trades.csv"),
        docs.calls.stream().map(Uploaded::filename).toList());

    List<InvoiceDocumentPayable> rows = store.last.documents();
    assertEquals(2, rows.size());
    assertEquals("DOC-1", rows.get(0).getSgDocId());
    assertEquals("DOC-2", rows.get(1).getSgDocId());
    assertEquals("MULTIPART", rows.get(0).getIncomingLine());
    assertEquals("PDF", rows.get(0).getDocumentType());
    assertEquals("TRADE_FILE", rows.get(1).getDocumentType());
  }

  @Test
  @DisplayName("embedded attachments are uploaded too, tagged with their own channel")
  void embeddedFilesAreUploaded() {
    RecordingDocs docs = new RecordingDocs();
    CapturingStore store = new CapturingStore();

    service(docs, store, List.of(PDF)).register(invoice(), List.of());

    assertEquals(1, docs.calls.size(), "the fallback channel is not a second-class one");
    assertEquals("DOC-1", store.last.documents().get(0).getSgDocId());
    assertEquals("EINVOICE_BODY", store.last.documents().get(0).getIncomingLine());
  }

  @Test
  @DisplayName("the supplier's reference goes up, because SG's does not exist yet")
  void uploadCarriesTheProviderReference() {
    // SG's reference is minted from the sequence when the row is written, which is after this.
    // The supplier's is what the document actually arrived with.
    RecordingDocs docs = new RecordingDocs();
    service(docs, new CapturingStore(), List.of()).register(invoice(), List.of(PDF));

    assertEquals("SUP-INV-1", docs.calls.get(0).invoiceReference());
  }

  @Test
  @DisplayName("no attachments means no upload and no document rows")
  void nothingToUpload() {
    RecordingDocs docs = new RecordingDocs();
    CapturingStore store = new CapturingStore();

    RegistrationOutcome outcome = service(docs, store, List.of()).register(invoice(), List.of());

    assertTrue(docs.calls.isEmpty());
    assertTrue(store.last.documents().isEmpty());
    assertEquals(RegistrationOutcome.Status.REGISTERED, outcome.status());
  }

  // ── Failure ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("a failed upload records the row with no handle and does not lose the invoice")
  void failedUploadIsRecordedNotFatal() {
    SgDocReferentialService refusing = new SgDocReferentialService() {
      @Override public String upload(ExtractedAttachment a, String ref) {
        throw new ReferentialUnavailableException("sgdoc", "store is down", true, null);
      }
      @Override public ExtractedAttachment download(String id) { return null; }
    };
    CapturingStore store = new CapturingStore();

    RegistrationOutcome outcome =
        service(refusing, store, List.of()).register(invoice(), List.of(PDF));

    assertEquals(1, store.last.documents().size(), "the document is still recorded as arrived");
    assertNull(store.last.documents().get(0).getSgDocId(),
        "a null handle is the honest record that the content is not yet retrievable");
    assertTrue(outcome.errors().stream()
            .anyMatch(e -> e.code() == ErrorCode.DOCUMENT_UPLOAD_FAILED),
        "an operator has to hear that bytes were received and not stored");
  }

  @Test
  @DisplayName("an upload failure does not refuse the sender's invoice")
  void uploadFailureIsNotTheSendersFault() {
    // They attached the document; our store would not take it. Refusing would ask them to
    // resend something that was never the problem.
    SgDocReferentialService refusing = new SgDocReferentialService() {
      @Override public String upload(ExtractedAttachment a, String ref) {
        throw new ReferentialUnavailableException("sgdoc", "store is down", true, null);
      }
      @Override public ExtractedAttachment download(String id) { return null; }
    };

    RegistrationOutcome outcome =
        service(refusing, new CapturingStore(), List.of()).register(invoice(), List.of(PDF));

    assertNull(outcome.lifecycleEvent(), "no REFUSED, no SUSPENDED");
    assertEquals(RegistrationOutcome.Status.INCOMPLETE, outcome.status());
  }

  @Test
  @DisplayName("an adapter that throws something unexpected is still contained")
  void unexpectedFailureIsContained() {
    // The port is contracted to raise ReferentialUnavailableException, but an adapter is code,
    // and one that throws something else must not take the whole registration with it.
    SgDocReferentialService broken = new SgDocReferentialService() {
      @Override public String upload(ExtractedAttachment a, String ref) {
        throw new IllegalStateException("NPE in the http client");
      }
      @Override public ExtractedAttachment download(String id) { return null; }
    };
    CapturingStore store = new CapturingStore();

    RegistrationOutcome outcome =
        service(broken, store, List.of()).register(invoice(), List.of(PDF));

    assertNotNull(store.last, "the row was still written");
    MappingError error = outcome.errors().stream()
        .filter(e -> e.code() == ErrorCode.DOCUMENT_UPLOAD_FAILED)
        .findFirst().orElseThrow();
    assertTrue(error.detail().contains("threw unexpectedly"));
    assertTrue(error.detail().contains("invoice.pdf"), "the failure names the file");
  }

  @Test
  @DisplayName("one bad file does not stop the others being stored")
  void oneFailureDoesNotStopTheRest() {
    SgDocReferentialService flaky = new SgDocReferentialService() {
      private final AtomicInteger calls = new AtomicInteger();

      @Override public String upload(ExtractedAttachment a, String ref) {
        if (calls.incrementAndGet() == 1) {
          throw new ReferentialUnavailableException("sgdoc", "too large", false, null);
        }
        return "DOC-2";
      }
      @Override public ExtractedAttachment download(String id) { return null; }
    };
    CapturingStore store = new CapturingStore();

    service(flaky, store, List.of()).register(invoice(), List.of(PDF, CSV));

    List<InvoiceDocumentPayable> rows = store.last.documents();
    assertEquals(2, rows.size(), "both documents arrived, so both are recorded");
    assertNull(rows.get(0).getSgDocId());
    assertEquals("DOC-2", rows.get(1).getSgDocId(),
        "the second file has nothing to do with the first one's failure");
  }

  @Test
  @DisplayName("uploaded files win outright over the document's own copies")
  void uploadedWinsOverEmbedded() {
    // A sender who uploads a corrected PDF while a superseded one is still embedded means the
    // upload. Registering both would leave a person to work out which one counts.
    RecordingDocs docs = new RecordingDocs();
    CapturingStore store = new CapturingStore();

    service(docs, store, List.of(PDF)).register(invoice(), List.of(CSV));

    assertEquals(List.of("trades.csv"), docs.calls.stream().map(Uploaded::filename).toList());
    assertEquals(1, store.last.documents().size());
    assertFalse(store.last.documents().stream()
            .anyMatch(d -> "invoice.pdf".equals(d.getDocumentName())),
        "the embedded copy is ignored, not merged");
  }
}
