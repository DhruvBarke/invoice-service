package com.example.invoice.service.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.invoice.mapper.einvoice.EInvoiceFacadeMapper;
import com.example.invoice.mapper.einvoice.MultipartExtractionService;
import com.example.invoice.mapper.einvoice.MultipartExtractionService.ExtractedAttachment;
import com.example.invoice.mapper.einvoice.model.invoice.Invoice;
import com.example.invoice.mapper.einvoice.model.invoice.Party;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoiceItem;
import com.example.invoice.mapper.einvoice.FeeTypeMatcher;
import com.example.invoice.service.registration.error.ErrorCode;
import com.example.invoice.service.registration.error.MappingError;
import com.example.invoice.service.registration.error.RegistrationOutcome;
import com.example.invoice.service.registration.port.InvoicePayableStore;
import com.example.invoice.service.registration.rule.BrokerageTradeFileRule;
import com.example.invoice.service.registration.rule.LineItemsPresentRule;
import com.example.invoice.service.registration.rule.ValidationContext;
import com.example.invoice.service.registration.rule.ValidationRegistry;
import com.example.invoice.service.registration.testsupport.Fixtures;
import com.example.invoice.service.registration.testsupport.Stubs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    return new InvoiceRegistrationService(
        new EInvoiceFacadeMapper(Stubs.lookup()), Stubs.matcher(), extractor,
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
      public List<ExtractedAttachment> extract(Invoice invoice) {
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
  @DisplayName("the persist request defaults its source and copies its item list")
  void persistRequestNormalisation() {
    List<InvoiceItem> mutable = new ArrayList<>();
    mutable.add(new InvoiceItem());

    InvoicePayableStore.PersistRequest blankSource = new InvoicePayableStore.PersistRequest(
        Business.MARK, "F01", "CUSTODY", "   ", null, mutable,
        RegistrationOutcome.decide(List.of()));
    assertEquals("EINVOICE", blankSource.source(),
        "a blank source is as good as absent — this pipeline only handles e-invoices");

    InvoicePayableStore.PersistRequest nullSource = new InvoicePayableStore.PersistRequest(
        Business.MARK, "F01", "CUSTODY", null, null, null,
        RegistrationOutcome.decide(List.of()));
    assertEquals("EINVOICE", nullSource.source());
    assertTrue(nullSource.items().isEmpty(), "a null item list normalises to empty");

    mutable.clear();
    assertEquals(1, blankSource.items().size(), "the request keeps its own copy");
  }

  // ── Rule guards ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("a trade file with a null body counts as absent")
  void tradeFileWithNullBytes() {
    ExtractedAttachment nullBody = new ExtractedAttachment("trades.csv", null, "text/csv");
    EInvoiceMarker marker = new EInvoiceMarker(
        "552120222", Business.MARK, "BROKERAGE_PRINCIPAL", "raw");

    List<com.example.invoice.service.registration.error.MappingError> errors =
        new BrokerageTradeFileRule().check(new ValidationContext(
            Business.MARK, marker, new Invoice(), null, List.of(),
            List.of(), List.of(nullBody)));

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
        null, null, List.of(), List.of())).size());
    assertEquals(1, rule.check(new ValidationContext(Business.MARK, marker, new Invoice(),
        null, List.of(), List.of(), List.of())).size());
    assertTrue(rule.check(new ValidationContext(Business.MARK, marker, new Invoice(),
        null, List.of(new InvoiceItem()), List.of(), List.of())).isEmpty());
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
    RegistrationOutcome outcome = new InvoiceRegistrationService(
        new EInvoiceFacadeMapper(Stubs.lookup()), racy, new MultipartExtractionService(),
        noRules(), store, new Stubs.RecordingPublisher(), new Stubs.RecordingNotifier())
        .register(Fixtures.loadInvoice("custody-with-lines.json"), List.of());

    MappingError feeError = outcome.errors().stream()
        .filter(e -> e.code() == ErrorCode.FEETYPE_UNRESOLVED)
        .findFirst().orElseThrow();
    assertTrue(feeError.detail().contains("no reason available"),
        "the fallback wording is what an operator reads when diagnosis comes back empty");
  }
}
