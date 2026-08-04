package com.sg.bootstrap.pipeline;

import com.sg.bootstrap.pipeline.testsupport.Fixtures;
import com.sg.bootstrap.pipeline.testsupport.Stubs;
import com.sg.domain.einvoice.InvoiceRegistrationServiceImpl;
import com.sg.domaininterface.service.InvoiceRegistrationService;
import com.sg.domain.einvoice.rule.BrokerageTradeFileRule;
import com.sg.domain.einvoice.rule.LineItemsPresentRule;
import com.sg.domain.einvoice.rule.ValidationRegistry;
import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.model.einvoice.EInvoiceMarker;
import com.sg.domaininterface.model.einvoice.EInvoiceMarkerParser;
import com.sg.domaininterface.model.einvoice.error.ErrorCode;
import com.sg.domaininterface.model.einvoice.error.MappingError;
import com.sg.domaininterface.model.einvoice.error.RegistrationOutcome;
import com.sg.domaininterface.model.invoice.ExtractedAttachment;
import com.sg.domaininterface.model.invoice.Invoice;
import com.sg.domaininterface.model.payableinvoice.InvoiceDocumentPayable;
import com.sg.domaininterface.model.payableinvoice.InvoiceItem;
import com.sg.domaininterface.port.einvoice.InvoicePayableStore;
import com.sg.domaininterface.rule.einvoice.AttachmentChannel;
import com.sg.domaininterface.rule.einvoice.ValidationContext;
import com.sg.mapper.einvoice.EInvoiceFacadeMapper;
import com.sg.mapper.einvoice.EInvoiceMappingAdapter;
import com.sg.mapper.einvoice.FeeTypeMatcher;
import com.sg.mapper.einvoice.MultipartExtractionService.Result;
import com.sg.mapper.einvoice.MultipartExtractionService.Status;
import com.sg.mapper.einvoice.MultipartExtractionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-sided arms of multi-clause conditions, and the defensive paths the behavioural
 * suites do not provoke.
 */
class RegistrationBoundaryCasesTest {

  private static ValidationRegistry noRules() {
    return ValidationRegistry.builder().build();
  }

  private static InvoiceRegistrationService service(
      MultipartExtractionService extractor, Stubs.RecordingStore store) {
    return new InvoiceRegistrationServiceImpl(
        new EInvoiceMappingAdapter(
            new EInvoiceFacadeMapper(Stubs.lookup()), Stubs.matcher(), extractor),
        noRules(), store, new Stubs.RecordingPublisher(), new Stubs.RecordingNotifier());
  }

  // ── Marker parsing ────────────────────────────────────────────────────────

  @Test
  @DisplayName("a trailing separator leaves the business readable, only the fee type absent")
  void trailingSeparatorKeepsTheBusiness() {
    // The business token sits BETWEEN the two separators. Reading to end-of-string here would
    // swallow the trailing underscore and lose an otherwise perfectly good business.
    EInvoiceMarker marker = EInvoiceMarkerParser.parse("552120222_MARK_");
    assertEquals("552120222", marker.siren());
    assertEquals(Business.MARK, marker.business());
    assertNull(marker.feeType());
  }

  @Test
  @DisplayName("a fee-type tail of only wide whitespace is treated as absent")
  void wideWhitespaceTailIsAbsent() {
    // trim() only strips through U+0020, so an EM SPACE (U+2003) survives it — but
    // isBlank() still calls it whitespace. Copy-pasted referential data carries these, and a
    // tail that says nothing must not reach the matcher as a token that can never resolve.
    //
    // Note U+00A0 does NOT qualify: Character.isWhitespace excludes non-breaking spaces, so a
    // NBSP tail is a real (if useless) token and is reported as such.
    EInvoiceMarker blank = EInvoiceMarkerParser.parse("552120222_MARK_ ");
    assertEquals(Business.MARK, blank.business());
    assertNull(blank.feeType(), "a tail that says nothing is no tail at all");

    EInvoiceMarker nbsp = EInvoiceMarkerParser.parse("552120222_MARK_ ");
    assertEquals(" ", nbsp.feeType(),
        "a non-breaking space is not whitespace to isBlank(), so it survives as a token");
  }

  // ── Attachment extraction failure ─────────────────────────────────────────

  @Test
  @DisplayName("an extractor that throws is captured, and the pipeline still completes")
  void extractorFailureIsCaptured() {
    MultipartExtractionService exploding = new MultipartExtractionService() {
      @Override
      public List<Result> extractDetailed(Invoice invoice) {
        throw new IllegalStateException("attachment decoder blew up");
      }
    };
    Stubs.RecordingStore store = new Stubs.RecordingStore();

    RegistrationOutcome outcome = service(exploding, store)
        .register(Fixtures.loadInvoice("custody-with-lines.json"), List.of());

    assertTrue(outcome.errors().stream().anyMatch(e ->
            e.code() == ErrorCode.MAPPING_ERROR
                && e.detail().contains("attachment extractor failed")),
        "the failure is recorded rather than propagated to the caller");
    assertEquals(1, store.calls, "the row is still written");
  }

  // ── The endpoint accessor's null guards ───────────────────────────────────

  @Test
  @DisplayName("an invoice whose customer party has no party block is malformed, not fatal")
  void customerPartyWithoutPartyBlock() {
    Invoice inv = Fixtures.loadInvoice("custody-with-lines.json");
    inv.getAccountingCustomerParty().setParty(null);

    Stubs.RecordingStore store = new Stubs.RecordingStore();
    RegistrationOutcome outcome =
        service(new MultipartExtractionService(), store).register(inv, List.of());

    assertTrue(outcome.errors().stream()
        .anyMatch(e -> e.code() == ErrorCode.MARKER_MALFORMED));
  }

  @Test
  @DisplayName("an endpoint element with no value is malformed")
  void endpointWithoutValue() {
    Invoice inv = Fixtures.loadInvoice("custody-with-lines.json");
    inv.getAccountingCustomerParty().getParty().getEndpointId().setValue(null);

    RegistrationOutcome outcome = service(new MultipartExtractionService(),
        new Stubs.RecordingStore()).register(inv, List.of());

    assertTrue(outcome.errors().stream()
        .anyMatch(e -> e.code() == ErrorCode.MARKER_MALFORMED));
  }

  // ── PersistRequest normalisation ──────────────────────────────────────────

  @Test
  @DisplayName("the persist request defaults its invoice flow and copies every collection")
  void persistRequestNormalisation() {
    List<InvoiceItem> mutableItems = new ArrayList<>();
    mutableItems.add(new InvoiceItem());
    List<InvoiceDocumentPayable> mutableDocs = new ArrayList<>();
    mutableDocs.add(InvoiceDocumentPayable.builder().documentName("a.pdf").build());

    InvoicePayableStore.PersistRequest blankFlow = new InvoicePayableStore.PersistRequest(
        Business.MARK, "F01", "CUSTODY", "   ", null, mutableItems, mutableDocs,
        RegistrationOutcome.decide(List.of()));
    assertEquals("EINVOICE", blankFlow.invoiceFlow(),
        "a blank flow is as good as absent — this pipeline only handles e-invoices, and a row "
            + "with no invoice_flow could not be told apart from a manual one");

    InvoicePayableStore.PersistRequest allNull = new InvoicePayableStore.PersistRequest(
        Business.MARK, "F01", "CUSTODY", null, null, null, null,
        RegistrationOutcome.decide(List.of()));
    assertEquals("EINVOICE", allNull.invoiceFlow());
    assertTrue(allNull.items().isEmpty(), "a null item list normalises to empty");
    assertTrue(allNull.documents().isEmpty());

    mutableItems.clear();
    mutableDocs.clear();
    assertEquals(1, blankFlow.items().size(), "the request keeps its own copy");
    assertEquals(1, blankFlow.documents().size());
  }

  @Test
  @DisplayName("an upload supersedes the copy embedded in the document")
  void uploadSupersedesEmbedded() {
    ExtractedAttachment fromBody =
        new ExtractedAttachment("body.pdf", new byte[] {1}, "application/pdf");
    ExtractedAttachment fromUpload =
        new ExtractedAttachment("upload.csv", new byte[] {2}, "text/csv");

    MultipartExtractionService bodyOnly = new MultipartExtractionService() {
      @Override public List<Result> extractDetailed(Invoice invoice) {
        return List.of(new Result("body.pdf", fromBody, Status.OK));
      }
    };

    Stubs.RecordingStore store = new Stubs.RecordingStore();
    service(bodyOnly, store)
        .register(Fixtures.loadInvoice("custody-with-lines.json"), List.of(fromUpload));

    // One row, not two. A sender who uploads a corrected file while a superseded copy is still
    // embedded in the document means the upload; registering both would leave a person to work
    // out which one counts, and the embedded copy is the one that is stale by construction.
    List<InvoiceDocumentPayable> docs = store.last.get().documents();
    assertEquals(1, docs.size(), "the embedded copy is ignored, not merged");
    assertEquals("upload.csv", docs.get(0).getDocumentName());
    assertEquals("MULTIPART", docs.get(0).getIncomingLine(),
        "the channel is recorded, so a later reader can tell an upload from an embedded copy");
  }

  @Test
  @DisplayName("with no upload, the embedded copy is used and labelled as such")
  void embeddedIsTheFallback() {
    ExtractedAttachment fromBody =
        new ExtractedAttachment("body.pdf", new byte[] {1}, "application/pdf");
    MultipartExtractionService bodyOnly = new MultipartExtractionService() {
      @Override public List<Result> extractDetailed(Invoice invoice) {
        return List.of(new Result("body.pdf", fromBody, Status.OK));
      }
    };

    Stubs.RecordingStore store = new Stubs.RecordingStore();
    service(bodyOnly, store)
        .register(Fixtures.loadInvoice("custody-with-lines.json"), List.of());

    List<InvoiceDocumentPayable> docs = store.last.get().documents();
    assertEquals(1, docs.size());

    InvoiceDocumentPayable body = docs.get(0);
    assertEquals("body.pdf", body.getDocumentName());
    assertEquals("PDF", body.getDocumentType());
    assertEquals("EINVOICE_BODY", body.getIncomingLine(),
        "labelled as the document's own copy, so an operator asking why no file was uploaded "
            + "is not told one was");

    // Metadata only. The content belongs in SGDoc; sg_doc_id stays null until an uploader
    // exists to fill it, and a null handle is the honest record of that.
    assertNull(body.getSgDocId());
  }

  @Test
  @DisplayName("the document type is read off the filename, and defaults to OTHER")
  void documentTypeClassification() {
    assertEquals("PDF", typeOf("invoice.PDF"), "the extension is matched case-insensitively");
    assertEquals("TRADE_FILE", typeOf("trades.csv"));
    assertEquals("TRADE_FILE", typeOf("trades.xlsx"));
    assertEquals("TRADE_FILE", typeOf("trades.xls"), "the legacy Excel format counts too");
    assertEquals("OTHER", typeOf("notes.txt"));
    assertEquals("OTHER", typeOf(null),
        "an unnamed attachment is still a document, so it classifies rather than throwing");
  }

  /** Through the public factory rather than the package-private helper behind it. */
  private static String typeOf(String filename) {
    return InvoiceDocumentPayable
        .fromAttachment(new ExtractedAttachment(filename, new byte[] {1}, "application/pdf"),
            AttachmentChannel.MULTIPART.name())
        .getDocumentType();
  }

  // ── Rule guards ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("a trade file with a null body counts as absent")
  void tradeFileWithNullBytes() {
    ExtractedAttachment nullBody = new ExtractedAttachment("trades.csv", null, "text/csv");
    EInvoiceMarker marker = new EInvoiceMarker(
        "552120222", Business.MARK, "BROKERAGE_PRINCIPAL", "raw");

    List<com.sg.domaininterface.model.einvoice.error.MappingError> errors =
        new BrokerageTradeFileRule().check(new ValidationContext(
            Business.MARK, marker, new Invoice(), null, List.of(),
            List.of(nullBody), AttachmentChannel.MULTIPART));

    assertEquals(1, errors.size(), "a file with no content is not a trade file");
    assertEquals(ErrorCode.MISSING_TRADE_FILE, errors.get(0).code());
  }

  @Test
  @DisplayName("the line-items rule distinguishes an empty list from a null one")
  void lineItemsRuleHandlesBothAbsentForms() {
    EInvoiceMarker marker =
        new EInvoiceMarker("552120222", Business.MARK, "CUSTODY", "raw");
    LineItemsPresentRule rule = new LineItemsPresentRule();

    // ValidationContext normalises null to empty, so both callers land on the same branch —
    // which is exactly the guarantee worth pinning.
    assertEquals(1, rule.check(new ValidationContext(Business.MARK, marker, new Invoice(),
        null, null, List.of(), AttachmentChannel.EINVOICE_BODY)).size());
    assertEquals(1, rule.check(new ValidationContext(Business.MARK, marker, new Invoice(),
        null, List.of(), List.of(), AttachmentChannel.EINVOICE_BODY)).size());
    assertTrue(rule.check(new ValidationContext(Business.MARK, marker, new Invoice(),
        null, List.of(new InvoiceItem()), List.of(), AttachmentChannel.EINVOICE_BODY)).isEmpty());
  }

  // ── Fee-type failure with no stated reason ────────────────────────────────

  @Test
  @DisplayName("an unresolved fee type still reports something when no reason is available")
  void unresolvedFeeTypeWithoutAReason() {
    // explainFailure returns null only when the code would actually resolve — a state the
    // orchestrator can reach if the referential changes between the two calls. The message
    // must stay readable rather than rendering "null".
    Invoice inv = Fixtures.loadInvoice("ambiguous-feetype.json");
    RegistrationOutcome outcome = service(new MultipartExtractionService(),
        new Stubs.RecordingStore()).register(inv, List.of());

    assertTrue(outcome.errors().stream()
        .filter(e -> e.code() == ErrorCode.FEETYPE_UNRESOLVED)
        .allMatch(e -> !e.detail().contains("null")),
        "the operator-facing detail must never read 'null'");
  }

  // ── Fee-type diagnosis when the referential moves underneath us ────────────

  @Test
  @DisplayName("an unresolved fee type still reads sensibly when no reason can be produced")
  void unresolvedFeeTypeWithNoReasonAvailable() {
    // resolveOrNull and explainFailure are two separate reads of the referential. If it gains
    // the entry between them, the first says "unresolved" and the second says "…actually it
    // resolves", returning null. The message must stay readable rather than printing "null".
    Map<String, String> empty = Map.of();
    Map<String, String> populated = Map.of("F01", "CUSTODY");
    AtomicInteger call = new AtomicInteger();
    FeeTypeMatcher racy = new FeeTypeMatcher(() -> call.getAndIncrement() == 0 ? empty : populated);

    Stubs.RecordingStore store = new Stubs.RecordingStore();
    RegistrationOutcome outcome = new InvoiceRegistrationServiceImpl(
        new EInvoiceMappingAdapter(
            new EInvoiceFacadeMapper(Stubs.lookup()), racy, new MultipartExtractionService()),
        noRules(), store, new Stubs.RecordingPublisher(), new Stubs.RecordingNotifier())
        .register(Fixtures.loadInvoice("custody-with-lines.json"), List.of());

    MappingError feeError = outcome.errors().stream()
        .filter(e -> e.code() == ErrorCode.FEETYPE_UNRESOLVED)
        .findFirst().orElseThrow();
    assertTrue(feeError.detail().contains("no reason available"),
        "the fallback wording is what an operator reads when diagnosis comes back empty");
  }
}
