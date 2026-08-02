package com.example.invoice.service.registration.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.invoice.mapper.einvoice.MultipartExtractionService.ExtractedAttachment;
import com.example.invoice.mapper.einvoice.model.invoice.Invoice;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoiceItem;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoicePayable;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoicePayableModel;
import com.example.invoice.service.registration.Business;
import com.example.invoice.service.registration.EInvoiceMarker;
import com.example.invoice.service.registration.error.ErrorCode;
import com.example.invoice.service.registration.error.MappingError;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Each rule in isolation, including the branches the end-to-end fixtures don't reach. */
class ValidationRulesTest {

  private static EInvoiceMarker marker(Business b, String feeType) {
    return new EInvoiceMarker("552120222", b, feeType, "552120222_" + b + "_" + feeType);
  }

  private static ValidationContext ctx(EInvoiceMarker m, InvoicePayableModel model,
                                       List<InvoiceItem> items,
                                       List<ExtractedAttachment> jsonAtt,
                                       List<ExtractedAttachment> multipartAtt) {
    return new ValidationContext(m.business(), m, new Invoice(), model, items, jsonAtt, multipartAtt);
  }

  private static InvoicePayableModel modelWithRef(String providerRef) {
    InvoicePayableModel model = new InvoicePayableModel();
    InvoicePayable payable = new InvoicePayable();
    payable.setProviderReference(providerRef);
    model.setInvoicePayable(payable);
    return model;
  }

  private static ExtractedAttachment file(String name) {
    return new ExtractedAttachment(name, "x".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
  }

  // ── ValidationContext ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("ValidationContext")
  class Context {

    @Test
    @DisplayName("null collections normalise to empty, so rules never null-check")
    void nullCollectionsNormalise() {
      ValidationContext c = new ValidationContext(
          Business.MARK, marker(Business.MARK, "CUSTODY"), new Invoice(), null, null, null, null);
      assertTrue(c.items().isEmpty());
      assertTrue(c.jsonAttachments().isEmpty());
      assertTrue(c.multipartAttachments().isEmpty());
      assertFalse(c.hasAnyAttachment());
    }

    @Test
    @DisplayName("hasAnyAttachment is true when either channel carries one")
    void hasAnyAttachmentAcrossBothChannels() {
      EInvoiceMarker m = marker(Business.MARK, "CUSTODY");
      assertTrue(ctx(m, null, List.of(), List.of(file("a.pdf")), List.of()).hasAnyAttachment());
      assertTrue(ctx(m, null, List.of(), List.of(), List.of(file("b.pdf"))).hasAnyAttachment());
      assertFalse(ctx(m, null, List.of(), List.of(), List.of()).hasAnyAttachment());
    }

    @Test
    @DisplayName("the source invoice and the marker are both mandatory")
    void mandatoryArguments() {
      EInvoiceMarker m = marker(Business.MARK, "CUSTODY");
      assertThrows(NullPointerException.class, () -> new ValidationContext(
          Business.MARK, m, null, null, List.of(), List.of(), List.of()));
      assertThrows(NullPointerException.class, () -> new ValidationContext(
          Business.MARK, null, new Invoice(), null, List.of(), List.of(), List.of()));
    }
  }

  // ── DuplicateInvoiceRule ──────────────────────────────────────────────────

  @Nested
  @DisplayName("DuplicateInvoiceRule")
  class Duplicate {

    @Test
    @DisplayName("fires when the lookup reports an existing REGISTERED row")
    void firesOnDuplicate() {
      List<MappingError> out = new DuplicateInvoiceRule(ref -> true)
          .check(ctx(marker(Business.MARK, "CUSTODY"), modelWithRef("CUS1"),
              List.of(), List.of(), List.of()));
      assertEquals(1, out.size());
      assertEquals(ErrorCode.DUPLICATE_INVOICE, out.get(0).code());
      assertTrue(out.get(0).detail().contains("invoice already exists"));
    }

    @Test
    @DisplayName("passes when the lookup finds nothing")
    void passesWhenNotDuplicate() {
      assertTrue(new DuplicateInvoiceRule(ref -> false)
          .check(ctx(marker(Business.MARK, "CUSTODY"), modelWithRef("CUS1"),
              List.of(), List.of(), List.of())).isEmpty());
    }

    @Test
    @DisplayName("cannot check a duplicate without a model — passes rather than guessing")
    void passesWhenModelAbsent() {
      DuplicateInvoiceRule rule = new DuplicateInvoiceRule(ref -> true);
      assertTrue(rule.check(ctx(marker(Business.MARK, "CUSTODY"), null,
          List.of(), List.of(), List.of())).isEmpty());
      assertTrue(rule.check(ctx(marker(Business.MARK, "CUSTODY"), new InvoicePayableModel(),
          List.of(), List.of(), List.of())).isEmpty(),
          "a model with no payable carries no provider reference to match on");
    }

    @ParameterizedTest(name = "provider reference [{0}] is not matchable")
    @ValueSource(strings = {"", "   "})
    @DisplayName("a blank provider reference is not matchable")
    void passesWhenReferenceBlank(String ref) {
      assertTrue(new DuplicateInvoiceRule(r -> true)
          .check(ctx(marker(Business.MARK, "CUSTODY"), modelWithRef(ref),
              List.of(), List.of(), List.of())).isEmpty());
    }

    @Test
    @DisplayName("a null provider reference is not matchable")
    void passesWhenReferenceNull() {
      assertTrue(new DuplicateInvoiceRule(r -> true)
          .check(ctx(marker(Business.MARK, "CUSTODY"), modelWithRef(null),
              List.of(), List.of(), List.of())).isEmpty());
    }

    @Test
    @DisplayName("the lookup is mandatory")
    void lookupMandatory() {
      assertThrows(NullPointerException.class, () -> new DuplicateInvoiceRule(null));
    }
  }

  // ── AttachmentPresentRule ─────────────────────────────────────────────────

  @Nested
  @DisplayName("AttachmentPresentRule")
  class Attachments {

    @Test
    @DisplayName("fires only when both channels are empty")
    void firesWhenNoAttachmentAnywhere() {
      List<MappingError> out = new AttachmentPresentRule()
          .check(ctx(marker(Business.MARK, "CUSTODY"), null, List.of(), List.of(), List.of()));
      assertEquals(1, out.size());
      assertEquals(ErrorCode.MISSING_ATTACHMENT, out.get(0).code());
    }

    @Test
    @DisplayName("passes on a JSON-embedded attachment")
    void passesOnJsonAttachment() {
      assertTrue(new AttachmentPresentRule()
          .check(ctx(marker(Business.MARK, "CUSTODY"), null, List.of(),
              List.of(file("a.pdf")), List.of())).isEmpty());
    }

    @Test
    @DisplayName("passes on a multipart attachment")
    void passesOnMultipartAttachment() {
      assertTrue(new AttachmentPresentRule()
          .check(ctx(marker(Business.MARK, "CUSTODY"), null, List.of(),
              List.of(), List.of(file("a.pdf")))).isEmpty());
    }
  }

  // ── BrokerageTradeFileRule ────────────────────────────────────────────────

  @Nested
  @DisplayName("BrokerageTradeFileRule")
  class TradeFile {

    private final BrokerageTradeFileRule rule = new BrokerageTradeFileRule();

    @ParameterizedTest(name = "{0} requires a trade file")
    @ValueSource(strings = {
        "BROKERAGE_PRINCIPAL", "BROKERAGE_AGENCY",
        "brokerage-principal", "brokerage agency", "BrOkErAgE_PrInCiPaL"})
    @DisplayName("separator and case variants all resolve to the brokerage family")
    void firesForEveryBrokerageSpelling(String feeType) {
      List<MappingError> out = rule.check(
          ctx(marker(Business.MARK, feeType), null, List.of(), List.of(), List.of()));
      assertEquals(1, out.size(), "spelling [" + feeType + "] should be recognised as brokerage");
      assertEquals(ErrorCode.MISSING_TRADE_FILE, out.get(0).code());
    }

    @ParameterizedTest(name = "a {0} file satisfies the rule")
    @ValueSource(strings = {"trades.csv", "trades.xlsx", "TRADES.CSV", "Trades.XLSX"})
    @DisplayName("csv and xlsx satisfy it regardless of filename case")
    void passesWithTradeFile(String filename) {
      assertTrue(rule.check(ctx(marker(Business.MARK, "BROKERAGE_PRINCIPAL"), null,
          List.of(), List.of(), List.of(file(filename)))).isEmpty());
    }

    @Test
    @DisplayName("a trade file embedded in the JSON body also satisfies it")
    void passesWithJsonChannelTradeFile() {
      assertTrue(rule.check(ctx(marker(Business.MARK, "BROKERAGE_AGENCY"), null,
          List.of(), List.of(file("trades.csv")), List.of())).isEmpty());
    }

    @Test
    @DisplayName("a PDF is not a trade file")
    void pdfIsNotATradeFile() {
      assertFalse(rule.check(ctx(marker(Business.MARK, "BROKERAGE_PRINCIPAL"), null,
          List.of(), List.of(), List.of(file("invoice.pdf")))).isEmpty());
    }

    @Test
    @DisplayName("an empty trade file counts as absent")
    void emptyTradeFileIsAbsent() {
      ExtractedAttachment empty = new ExtractedAttachment("trades.csv", new byte[0], "text/csv");
      assertFalse(rule.check(ctx(marker(Business.MARK, "BROKERAGE_PRINCIPAL"), null,
          List.of(), List.of(), List.of(empty))).isEmpty());
    }

    @Test
    @DisplayName("an attachment with no filename is skipped safely")
    void nullFilenameSkipped() {
      ExtractedAttachment noName = new ExtractedAttachment(
          null, "x".getBytes(StandardCharsets.UTF_8), "text/csv");
      assertFalse(rule.check(ctx(marker(Business.MARK, "BROKERAGE_PRINCIPAL"), null,
          List.of(), List.of(), List.of(noName))).isEmpty());
    }

    @Test
    @DisplayName("non-brokerage fee types are none of this rule's business")
    void ignoresNonBrokerage() {
      assertTrue(rule.check(ctx(marker(Business.MARK, "CUSTODY"), null,
          List.of(), List.of(), List.of())).isEmpty());
    }

    @Test
    @DisplayName("an unresolved fee type is none of this rule's business either")
    void ignoresNullFeeType() {
      EInvoiceMarker m = new EInvoiceMarker("552120222", Business.MARK, null, "raw");
      assertTrue(rule.check(ctx(m, null, List.of(), List.of(), List.of())).isEmpty());
    }
  }

  // ── LineItemsPresentRule ──────────────────────────────────────────────────

  @Nested
  @DisplayName("LineItemsPresentRule")
  class LineItems {

    private final LineItemsPresentRule rule = new LineItemsPresentRule();

    @ParameterizedTest(name = "{0} requires line items")
    @ValueSource(strings = {"CUSTODY", "EXCHANGE", "CLEARING", "custody", "Exchange"})
    @DisplayName("fires for the three fee types that require lines")
    void firesWhenNoLines(String feeType) {
      List<MappingError> out = rule.check(
          ctx(marker(Business.MARK, feeType), null, List.of(), List.of(), List.of()));
      assertEquals(1, out.size());
      assertEquals(ErrorCode.EMPTY_LINE_ITEMS, out.get(0).code());
      assertNotNull(out.get(0).detectedAt());
    }

    @Test
    @DisplayName("passes when at least one line is present")
    void passesWithLines() {
      assertTrue(rule.check(ctx(marker(Business.MARK, "CUSTODY"), null,
          List.of(new InvoiceItem()), List.of(), List.of())).isEmpty());
    }

    @Test
    @DisplayName("brokerage does not require line items")
    void ignoresBrokerage() {
      assertTrue(rule.check(ctx(marker(Business.MARK, "BROKERAGE_PRINCIPAL"), null,
          List.of(), List.of(), List.of())).isEmpty());
    }

    @Test
    @DisplayName("an unresolved fee type is skipped")
    void ignoresNullFeeType() {
      EInvoiceMarker m = new EInvoiceMarker("552120222", Business.MARK, null, "raw");
      assertTrue(rule.check(ctx(m, null, List.of(), List.of(), List.of())).isEmpty());
    }
  }

  // ── ValidationRule#id + registry ──────────────────────────────────────────

  @Nested
  @DisplayName("rule ids and the registry")
  class Registry {

    @Test
    @DisplayName("the default id is kebab-case with the Rule suffix dropped")
    void defaultIdIsKebabCase() {
      assertEquals("duplicate-invoice", new DuplicateInvoiceRule(r -> false).id());
      assertEquals("attachment-present", new AttachmentPresentRule().id());
      assertEquals("brokerage-trade-file", new BrokerageTradeFileRule().id());
      assertEquals("line-items-present", new LineItemsPresentRule().id());
    }

    @Test
    @DisplayName("a lambda rule still gets a usable id")
    void lambdaRuleHasAnId() {
      ValidationRule lambda = c -> List.of();
      assertNotNull(lambda.id(), "config keys need an id even for inline rules");
    }

    @Test
    @DisplayName("rules are returned per business, in registration order")
    void rulesAreScopedPerBusiness() {
      ValidationRule a = c -> List.of();
      ValidationRule b = c -> List.of();
      ValidationRegistry reg = ValidationRegistry.builder()
          .add(Business.MARK, a)
          .add(Business.MARK, b)
          .add(Business.SGSS, a)
          .build();

      assertEquals(List.of(a, b), reg.rulesFor(Business.MARK));
      assertEquals(List.of(a), reg.rulesFor(Business.SGSS));
      assertEquals(Business.MARK, reg.configuredBusinesses().iterator().next());
      assertEquals(2, reg.configuredBusinesses().size());
    }

    @Test
    @DisplayName("an unconfigured business gets the empty set, not a failure")
    void unconfiguredBusinessGetsEmptySet() {
      ValidationRegistry reg = ValidationRegistry.builder().build();
      assertTrue(reg.rulesFor(Business.GTPS).isEmpty(),
          "onboarding a business must not start rejecting its invoices by default");
      assertTrue(reg.rulesFor(null).isEmpty(), "an unresolved business runs no rules at all");
    }

    @Test
    @DisplayName("addForAll registers one rule against several businesses")
    void addForAllFansOut() {
      ValidationRule shared = c -> List.of();
      ValidationRegistry reg = ValidationRegistry.builder()
          .addForAll(List.of(Business.MARK, Business.SGSS, Business.GTPS), shared)
          .build();

      assertSame(shared, reg.rulesFor(Business.MARK).get(0));
      assertSame(shared, reg.rulesFor(Business.SGSS).get(0));
      assertSame(shared, reg.rulesFor(Business.GTPS).get(0));
      assertTrue(reg.rulesFor(Business.GLBA).isEmpty());
    }

    @Test
    @DisplayName("the builder rejects null arguments")
    void builderRejectsNulls() {
      ValidationRegistry.Builder b = ValidationRegistry.builder();
      assertThrows(NullPointerException.class, () -> b.add(null, c -> List.of()));
      assertThrows(NullPointerException.class, () -> b.add(Business.MARK, null));
    }

    @Test
    @DisplayName("the returned rule list is immutable")
    void returnedListIsImmutable() {
      ValidationRegistry reg = ValidationRegistry.builder()
          .add(Business.MARK, c -> List.of()).build();
      assertThrows(UnsupportedOperationException.class,
          () -> reg.rulesFor(Business.MARK).add(c -> List.of()));
    }
  }
}
