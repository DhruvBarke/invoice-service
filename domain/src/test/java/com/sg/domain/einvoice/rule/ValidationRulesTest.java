package com.sg.domain.einvoice.rule;

import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.model.einvoice.EInvoiceMarker;
import com.sg.domaininterface.model.einvoice.error.ErrorCode;
import com.sg.domaininterface.model.einvoice.error.MappingError;
import com.sg.domaininterface.model.invoice.ExtractedAttachment;
import com.sg.domaininterface.model.invoice.Invoice;
import com.sg.domaininterface.model.payableinvoice.InvoiceItem;
import com.sg.domaininterface.model.payableinvoice.InvoicePayable;
import com.sg.domaininterface.model.payableinvoice.InvoicePayableModel;
import com.sg.domaininterface.rule.einvoice.AttachmentChannel;
import com.sg.domaininterface.rule.einvoice.ValidationContext;
import com.sg.domaininterface.rule.einvoice.ValidationRule;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each rule in isolation, including the branches the end-to-end fixtures don't reach. */
class ValidationRulesTest {

  private static EInvoiceMarker marker(Business b, String feeType) {
    return new EInvoiceMarker("552120222", b, feeType, "552120222_" + b + "_" + feeType);
  }

  private static ValidationContext ctx(EInvoiceMarker m, InvoicePayableModel model,
                                       List<InvoiceItem> items,
                                       List<ExtractedAttachment> attachments,
                                       AttachmentChannel channel) {
    return new ValidationContext(m.business(), m, new Invoice(), model, items, attachments, channel);
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
      assertTrue(c.attachments().isEmpty());
      assertFalse(c.hasAnyAttachment());
      assertEquals(AttachmentChannel.EINVOICE_BODY, c.channel(),
          "an unspecified channel defaults to the document's own, which is the fallback the "
              + "orchestrator uses when nothing was uploaded");
    }

    @Test
    @DisplayName("hasAnyAttachment reflects the winning channel, whichever it was")
    void hasAnyAttachmentOnEitherChannel() {
      EInvoiceMarker m = marker(Business.MARK, "CUSTODY");
      assertTrue(ctx(m, null, List.of(), List.of(file("a.pdf")), AttachmentChannel.MULTIPART)
          .hasAnyAttachment());
      assertTrue(ctx(m, null, List.of(), List.of(file("b.pdf")), AttachmentChannel.EINVOICE_BODY)
          .hasAnyAttachment());
      assertFalse(ctx(m, null, List.of(), List.of(), AttachmentChannel.MULTIPART)
          .hasAnyAttachment(),
          "an upload that carried no usable file is still an empty attachment set");
    }

    @Test
    @DisplayName("the source invoice and the marker are both mandatory")
    void mandatoryArguments() {
      EInvoiceMarker m = marker(Business.MARK, "CUSTODY");
      assertThrows(NullPointerException.class, () -> new ValidationContext(
          Business.MARK, m, null, null, List.of(), List.of(), AttachmentChannel.MULTIPART));
      assertThrows(NullPointerException.class, () -> new ValidationContext(
          Business.MARK, null, new Invoice(), null, List.of(), List.of(),
          AttachmentChannel.MULTIPART));
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
              List.of(), List.of(), AttachmentChannel.EINVOICE_BODY));
      assertEquals(1, out.size());
      assertEquals(ErrorCode.DUPLICATE_INVOICE, out.get(0).code());
      assertTrue(out.get(0).detail().contains("invoice already exists"));
    }

    @Test
    @DisplayName("passes when the lookup finds nothing")
    void passesWhenNotDuplicate() {
      assertTrue(new DuplicateInvoiceRule(ref -> false)
          .check(ctx(marker(Business.MARK, "CUSTODY"), modelWithRef("CUS1"),
              List.of(), List.of(), AttachmentChannel.EINVOICE_BODY)).isEmpty());
    }

    @Test
    @DisplayName("cannot check a duplicate without a model — passes rather than guessing")
    void passesWhenModelAbsent() {
      DuplicateInvoiceRule rule = new DuplicateInvoiceRule(ref -> true);
      assertTrue(rule.check(ctx(marker(Business.MARK, "CUSTODY"), null,
          List.of(), List.of(), AttachmentChannel.EINVOICE_BODY)).isEmpty());
      assertTrue(rule.check(ctx(marker(Business.MARK, "CUSTODY"), new InvoicePayableModel(),
          List.of(), List.of(), AttachmentChannel.EINVOICE_BODY)).isEmpty(),
          "a model with no payable carries no provider reference to match on");
    }

    @ParameterizedTest(name = "provider reference [{0}] is not matchable")
    @ValueSource(strings = {"", "   "})
    @DisplayName("a blank provider reference is not matchable")
    void passesWhenReferenceBlank(String ref) {
      assertTrue(new DuplicateInvoiceRule(r -> true)
          .check(ctx(marker(Business.MARK, "CUSTODY"), modelWithRef(ref),
              List.of(), List.of(), AttachmentChannel.EINVOICE_BODY)).isEmpty());
    }

    @Test
    @DisplayName("a null provider reference is not matchable")
    void passesWhenReferenceNull() {
      assertTrue(new DuplicateInvoiceRule(r -> true)
          .check(ctx(marker(Business.MARK, "CUSTODY"), modelWithRef(null),
              List.of(), List.of(), AttachmentChannel.EINVOICE_BODY)).isEmpty());
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
          .check(ctx(marker(Business.MARK, "CUSTODY"), null, List.of(), List.of(), AttachmentChannel.EINVOICE_BODY));
      assertEquals(1, out.size());
      assertEquals(ErrorCode.MISSING_ATTACHMENT, out.get(0).code());
    }

    @Test
    @DisplayName("passes on a JSON-embedded attachment")
    void passesOnJsonAttachment() {
      assertTrue(new AttachmentPresentRule()
          .check(ctx(marker(Business.MARK, "CUSTODY"), null, List.of(),
              List.of(file("a.pdf")), AttachmentChannel.EINVOICE_BODY)).isEmpty());
    }

    @Test
    @DisplayName("passes on a multipart attachment")
    void passesOnMultipartAttachment() {
      assertTrue(new AttachmentPresentRule()
          .check(ctx(marker(Business.MARK, "CUSTODY"), null, List.of(), List.of(file("a.pdf")), AttachmentChannel.MULTIPART)).isEmpty());
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
          ctx(marker(Business.MARK, feeType), null, List.of(), List.of(), AttachmentChannel.EINVOICE_BODY));
      assertEquals(1, out.size(), "spelling [" + feeType + "] should be recognised as brokerage");
      assertEquals(ErrorCode.MISSING_TRADE_FILE, out.get(0).code());
    }

    @ParameterizedTest(name = "a {0} file satisfies the rule")
    @ValueSource(strings = {"trades.csv", "trades.xlsx", "TRADES.CSV", "Trades.XLSX"})
    @DisplayName("csv and xlsx satisfy it regardless of filename case")
    void passesWithTradeFile(String filename) {
      assertTrue(rule.check(ctx(marker(Business.MARK, "BROKERAGE_PRINCIPAL"), null,
          List.of(), List.of(file(filename)), AttachmentChannel.MULTIPART)).isEmpty());
    }

    @Test
    @DisplayName("a trade file embedded in the JSON body also satisfies it")
    void passesWithJsonChannelTradeFile() {
      assertTrue(rule.check(ctx(marker(Business.MARK, "BROKERAGE_AGENCY"), null,
          List.of(), List.of(file("trades.csv")), AttachmentChannel.EINVOICE_BODY)).isEmpty());
    }

    @Test
    @DisplayName("a PDF is not a trade file")
    void pdfIsNotATradeFile() {
      assertFalse(rule.check(ctx(marker(Business.MARK, "BROKERAGE_PRINCIPAL"), null,
          List.of(), List.of(file("invoice.pdf")), AttachmentChannel.MULTIPART)).isEmpty());
    }

    @Test
    @DisplayName("an empty trade file counts as absent")
    void emptyTradeFileIsAbsent() {
      ExtractedAttachment empty = new ExtractedAttachment("trades.csv", new byte[0], "text/csv");
      assertFalse(rule.check(ctx(marker(Business.MARK, "BROKERAGE_PRINCIPAL"), null,
          List.of(), List.of(empty), AttachmentChannel.MULTIPART)).isEmpty());
    }

    @Test
    @DisplayName("an attachment with no filename is skipped safely")
    void nullFilenameSkipped() {
      ExtractedAttachment noName = new ExtractedAttachment(
          null, "x".getBytes(StandardCharsets.UTF_8), "text/csv");
      assertFalse(rule.check(ctx(marker(Business.MARK, "BROKERAGE_PRINCIPAL"), null,
          List.of(), List.of(noName), AttachmentChannel.MULTIPART)).isEmpty());
    }

    @Test
    @DisplayName("non-brokerage fee types are none of this rule's business")
    void ignoresNonBrokerage() {
      assertTrue(rule.check(ctx(marker(Business.MARK, "CUSTODY"), null,
          List.of(), List.of(), AttachmentChannel.EINVOICE_BODY)).isEmpty());
    }

    @Test
    @DisplayName("a zero-length file is not a trade file, whatever it is called")
    void zeroLengthFileIsNotATradeFile() {
      // A zero-byte upload is what a failed transfer looks like. Accepting it because the name
      // ends in .csv would clear the rule and leave the business with nothing to reconcile
      // against, which is precisely the situation the rule exists to catch.
      EInvoiceMarker m = marker(Business.MARK, "BROKERAGE_PRINCIPAL");
      ExtractedAttachment empty =
          new ExtractedAttachment("trades.csv", new byte[0], "text/csv");

      List<MappingError> out = new BrokerageTradeFileRule()
          .check(ctx(m, null, List.of(), List.of(empty), AttachmentChannel.MULTIPART));

      assertEquals(1, out.size());
      assertEquals(ErrorCode.MISSING_TRADE_FILE, out.get(0).code());
      assertTrue(out.get(0).detail().contains("MULTIPART"),
          "the channel is named, so the sender is told where to look");

      // Null content is the other shape the same failure takes: a part that announced itself
      // and delivered nothing.
      ExtractedAttachment noBody = new ExtractedAttachment("trades.csv", null, "text/csv");
      assertEquals(1, new BrokerageTradeFileRule()
          .check(ctx(m, null, List.of(), List.of(noBody), AttachmentChannel.MULTIPART)).size());
    }

    @Test
    @DisplayName("a fee type of null is not brokerage, so the rule stands down")
    void ignoresNullFeeType() {
      EInvoiceMarker m = new EInvoiceMarker("552120222", Business.MARK, null, "raw");
      assertTrue(rule.check(ctx(m, null, List.of(), List.of(), AttachmentChannel.MULTIPART)).isEmpty());
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
          ctx(marker(Business.MARK, feeType), null, List.of(), List.of(), AttachmentChannel.EINVOICE_BODY));
      assertEquals(1, out.size());
      assertEquals(ErrorCode.EMPTY_LINE_ITEMS, out.get(0).code());
      assertNotNull(out.get(0).detectedAt());
    }

    @Test
    @DisplayName("passes when at least one line is present")
    void passesWithLines() {
      assertTrue(rule.check(ctx(marker(Business.MARK, "CUSTODY"), null,
          List.of(new InvoiceItem()), List.of(), AttachmentChannel.EINVOICE_BODY)).isEmpty());
    }

    @Test
    @DisplayName("brokerage does not require line items")
    void ignoresBrokerage() {
      assertTrue(rule.check(ctx(marker(Business.MARK, "BROKERAGE_PRINCIPAL"), null,
          List.of(), List.of(), AttachmentChannel.EINVOICE_BODY)).isEmpty());
    }

    @Test
    @DisplayName("an unresolved fee type is skipped")
    void zeroLengthFileIsNotATradeFile() {
      // A zero-byte upload is what a failed transfer looks like. Accepting it because the name
      // ends in .csv would clear the rule and leave the business with nothing to reconcile
      // against, which is precisely the situation the rule exists to catch.
      EInvoiceMarker m = marker(Business.MARK, "BROKERAGE_PRINCIPAL");
      ExtractedAttachment empty =
          new ExtractedAttachment("trades.csv", new byte[0], "text/csv");

      List<MappingError> out = new BrokerageTradeFileRule()
          .check(ctx(m, null, List.of(), List.of(empty), AttachmentChannel.MULTIPART));

      assertEquals(1, out.size());
      assertEquals(ErrorCode.MISSING_TRADE_FILE, out.get(0).code());
      assertTrue(out.get(0).detail().contains("MULTIPART"),
          "the channel is named, so the sender is told where to look");

      // Null content is the other shape the same failure takes: a part that announced itself
      // and delivered nothing.
      ExtractedAttachment noBody = new ExtractedAttachment("trades.csv", null, "text/csv");
      assertEquals(1, new BrokerageTradeFileRule()
          .check(ctx(m, null, List.of(), List.of(noBody), AttachmentChannel.MULTIPART)).size());
    }

    @Test
    @DisplayName("a fee type of null is not brokerage, so the rule stands down")
    void ignoresNullFeeType() {
      EInvoiceMarker m = new EInvoiceMarker("552120222", Business.MARK, null, "raw");
      assertTrue(rule.check(ctx(m, null, List.of(), List.of(), AttachmentChannel.MULTIPART)).isEmpty());
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
