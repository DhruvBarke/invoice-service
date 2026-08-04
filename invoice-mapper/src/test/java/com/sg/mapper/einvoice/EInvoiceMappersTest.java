package com.sg.mapper.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.mapper.einvoice.DocumentReferenceMapper.AttachmentPayload;
import com.sg.domaininterface.model.invoice.AccountingCustomerParty;
import com.sg.domaininterface.model.invoice.AccountingSupplierParty;
import com.sg.domaininterface.model.invoice.AdditionalDocumentReference;
import com.sg.domaininterface.model.invoice.CodedValue;
import com.sg.domaininterface.model.invoice.CurrencyAmount;
import com.sg.domaininterface.model.invoice.Invoice;
import com.sg.domaininterface.model.invoice.InvoiceLine;
import com.sg.domaininterface.model.invoice.Item;
import com.sg.domaininterface.model.invoice.LegalMonetaryTotal;
import com.sg.domaininterface.model.invoice.Party;
import com.sg.domaininterface.model.invoice.PartyLegalEntity;
import com.sg.domaininterface.model.invoice.Quantity;
import com.sg.domaininterface.model.invoice.TaxSubtotal;
import com.sg.domaininterface.model.invoice.TaxTotal;
import com.sg.domaininterface.model.payableinvoice.InvoiceItem;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The einvoice sub-mappers: types, amounts, dates, parties, lines and attachments. */
class EInvoiceMappersTest {

  // ── InvoiceTypeMapper ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("InvoiceTypeMapper")
  class Types {

    @ParameterizedTest(name = "code {0} maps to {1}")
    @CsvSource({"380, DEBIT", "381, CREDIT", "384, CORRECTED", "999, UNKNOWN"})
    @DisplayName("UNTDID 1001 codes map to canonical labels")
    void codeToLabel(String code, String label) {
      CodedValue cv = new CodedValue();
      cv.setValue(code);
      assertEquals(label, InvoiceTypeMapper.toInvoiceType(cv));
    }

    @Test
    @DisplayName("an absent code is UNKNOWN rather than a crash")
    void absentCodeIsUnknown() {
      assertEquals("UNKNOWN", InvoiceTypeMapper.toInvoiceType(null));
      assertEquals("UNKNOWN", InvoiceTypeMapper.toInvoiceType(new CodedValue()));
    }

    @ParameterizedTest(name = "{0} maps back to {1}")
    @CsvSource({"DEBIT, 380", "CREDIT, 381", "CORRECTED, 384",
        "debit, 380", "SOMETHING_ELSE, 380"})
    @DisplayName("labels map back to codes, defaulting to a commercial invoice")
    void labelToCode(String label, String expectedCode) {
      assertEquals(expectedCode, InvoiceTypeMapper.toInvoiceTypeCode(label).getValue());
    }

    @Test
    @DisplayName("a null label defaults to the commercial-invoice code")
    void nullLabelDefaults() {
      assertEquals("380", InvoiceTypeMapper.toInvoiceTypeCode(null).getValue());
    }

    @Test
    @DisplayName("the round trip is stable for the three known types")
    void roundTrip() {
      for (String label : List.of("DEBIT", "CREDIT", "CORRECTED")) {
        assertEquals(label,
            InvoiceTypeMapper.toInvoiceType(InvoiceTypeMapper.toInvoiceTypeCode(label)));
      }
    }
  }

  // ── DateMapper ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("DateMapper")
  class Dates {

    @Test
    @DisplayName("an ISO date round-trips")
    void isoRoundTrip() {
      assertEquals(LocalDate.of(2026, 4, 14), DateMapper.parse("2026-04-14"));
      assertEquals("2026-04-14", DateMapper.format(LocalDate.of(2026, 4, 14)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "14/04/2026", "not-a-date", "2026-13-45"})
    @DisplayName("anything unparseable yields null rather than throwing mid-mapping")
    void unparseableYieldsNull(String raw) {
      assertNull(DateMapper.parse(raw));
    }

    @Test
    @DisplayName("formatting a null date yields null")
    void formatNull() {
      assertNull(DateMapper.format(null));
    }
  }

  // ── AmountMapper ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("AmountMapper")
  class Amounts {

    @Test
    @DisplayName("an amount carries its value and currency")
    void toAmount() {
      CurrencyAmount a = AmountMapper.toAmount(new BigDecimal("751.85"), "EUR");
      assertEquals(new BigDecimal("751.85"), a.getValue());
      assertEquals("EUR", a.getCurrencyID());
      assertNull(AmountMapper.toAmount(null, "EUR"), "no value means no amount element");
    }

    @Test
    @DisplayName("reading a value back tolerates an absent amount")
    void valueOf() {
      assertEquals(new BigDecimal("10.00"),
          AmountMapper.value(AmountMapper.toAmount(new BigDecimal("10.00"), "EUR")));
      assertNull(AmountMapper.value(null));
    }

    @Test
    @DisplayName("currency wraps and unwraps")
    void currencyRoundTrip() {
      assertEquals("EUR", AmountMapper.fromCodedCurrency(AmountMapper.toCodedCurrency("EUR")));
      assertNull(AmountMapper.toCodedCurrency(null));
      assertNull(AmountMapper.fromCodedCurrency(null));
    }

    @Test
    @DisplayName("the legal monetary total derives the tax-exclusive figure")
    void legalMonetaryTotal() {
      LegalMonetaryTotal t = AmountMapper.toLegalMonetaryTotal(
          new BigDecimal("751.85"), new BigDecimal("125.31"), "EUR");

      assertEquals(new BigDecimal("626.54"), t.getLineExtensionAmount().getValue());
      assertEquals(new BigDecimal("626.54"), t.getTaxExclusiveAmount().getValue());
      assertEquals(new BigDecimal("751.85"), t.getTaxInclusiveAmount().getValue());
      assertEquals(new BigDecimal("751.85"), t.getPayableAmount().getValue());
    }

    @Test
    @DisplayName("no VAT is treated as zero, not as missing")
    void legalMonetaryTotalWithoutVat() {
      LegalMonetaryTotal t =
          AmountMapper.toLegalMonetaryTotal(new BigDecimal("100.00"), null, "EUR");
      assertEquals(new BigDecimal("100.00"), t.getLineExtensionAmount().getValue());
    }

    @Test
    @DisplayName("without a total there is no monetary block at all")
    void legalMonetaryTotalNeedsATotal() {
      assertNull(AmountMapper.toLegalMonetaryTotal(null, new BigDecimal("1"), "EUR"));
    }

    @Test
    @DisplayName("the tax block carries one subtotal with scheme and category")
    void taxTotal() {
      List<TaxTotal> totals = AmountMapper.toTaxTotal(
          new BigDecimal("125.31"), new BigDecimal("20.00"), new BigDecimal("626.54"), "EUR");

      assertEquals(1, totals.size());
      TaxSubtotal sub = totals.get(0).getTaxSubtotal().get(0);
      assertEquals(new BigDecimal("626.54"), sub.getTaxableAmount().getValue());
      assertEquals(new BigDecimal("125.31"), sub.getTaxAmount().getValue());
      assertEquals("S", sub.getTaxCategory().getId().getValue());
      assertEquals("VAT", sub.getTaxCategory().getTaxScheme().getId().getValue());
      assertEquals(new BigDecimal("20.00"), sub.getTaxCategory().getPercent());
    }

    @Test
    @DisplayName("with neither amount nor rate the block is omitted")
    void taxTotalOmittedWhenNoVat() {
      assertTrue(AmountMapper.toTaxTotal(null, null, new BigDecimal("100"), "EUR").isEmpty());
    }

    @Test
    @DisplayName("a rate without an amount still produces a block, with zero tax")
    void taxTotalWithRateOnly() {
      List<TaxTotal> totals =
          AmountMapper.toTaxTotal(null, new BigDecimal("20.00"), new BigDecimal("100"), "EUR");
      assertEquals(BigDecimal.ZERO, totals.get(0).getTaxAmount().getValue());
    }

    @Test
    @DisplayName("an amount without a rate produces a block with a zero percent")
    void taxTotalWithAmountOnly() {
      List<TaxTotal> totals =
          AmountMapper.toTaxTotal(new BigDecimal("5.00"), null, new BigDecimal("100"), "EUR");
      assertEquals(BigDecimal.ZERO,
          totals.get(0).getTaxSubtotal().get(0).getTaxCategory().getPercent());
    }

    @Test
    @DisplayName("reading the first VAT figures back tolerates every absent level")
    void firstVatIsNullSafe() {
      assertNull(AmountMapper.firstVatAmount(null));
      assertNull(AmountMapper.firstVatAmount(List.of()));
      assertNull(AmountMapper.firstVatRate(null));
      assertNull(AmountMapper.firstVatRate(List.of()));

      TaxTotal empty = new TaxTotal();
      assertNull(AmountMapper.firstVatAmount(List.of(empty)), "no subtotal list");
      assertNull(AmountMapper.firstVatRate(List.of(empty)));

      TaxTotal noSubtotals = new TaxTotal();
      noSubtotals.setTaxSubtotal(List.of());
      assertNull(AmountMapper.firstVatAmount(List.of(noSubtotals)));
      assertNull(AmountMapper.firstVatRate(List.of(noSubtotals)));

      TaxTotal bareSubtotal = new TaxTotal();
      bareSubtotal.setTaxSubtotal(List.of(new TaxSubtotal()));
      assertNull(AmountMapper.firstVatAmount(List.of(bareSubtotal)), "subtotal with no amount");
      assertNull(AmountMapper.firstVatRate(List.of(bareSubtotal)), "subtotal with no category");
    }

    @Test
    @DisplayName("the first VAT figures are read off a populated block")
    void firstVatReadsThePopulatedBlock() {
      List<TaxTotal> totals = AmountMapper.toTaxTotal(
          new BigDecimal("125.31"), new BigDecimal("20.00"), new BigDecimal("626.54"), "EUR");
      assertEquals(new BigDecimal("125.31"), AmountMapper.firstVatAmount(totals));
      assertEquals(new BigDecimal("20.00"), AmountMapper.firstVatRate(totals));
    }
  }

  // ── PartyMapper ───────────────────────────────────────────────────────────

  @Nested
  @DisplayName("PartyMapper")
  class Parties {

    @Test
    @DisplayName("the provider becomes the supplier and SG the customer")
    void supplierAndCustomer() {
      AccountingSupplierParty supplier = PartyMapper.toSupplier("784608416", "EUROCLEAR");
      AccountingCustomerParty customer = PartyMapper.toCustomer("552120222", "SOCIETE GENERALE");

      assertEquals("784608416",
          supplier.getParty().getPartyLegalEntity().getCompanyId().getValue());
      assertEquals("EUROCLEAR",
          supplier.getParty().getPartyLegalEntity().getRegistrationName());
      assertEquals("552120222",
          customer.getParty().getPartyLegalEntity().getCompanyId().getValue());
    }

    @Test
    @DisplayName("a party carries the VAT tax scheme and the 0002 company scheme")
    void partySchemes() {
      Party p = PartyMapper.toSupplier("784608416", "EUROCLEAR").getParty();
      assertEquals("0002", p.getPartyLegalEntity().getCompanyId().getSchemeID());
      assertEquals("VAT", p.getPartyLegalEntity().getTaxScheme().getId().getValue());
      assertEquals("784608416", p.getPartyTaxScheme().get(0).getCompanyId().getValue());
      assertEquals("VAT", p.getPartyTaxScheme().get(0).getTaxScheme().getId().getValue());
    }

    @Test
    @DisplayName("EN16931 requires an address, so a placeholder is always emitted")
    void placeholderAddressIsAlwaysPresent() {
      Party p = PartyMapper.toSupplier("784608416", "EUROCLEAR").getParty();
      assertEquals("PARIS", p.getPostalAddress().getCityName());
      assertEquals("75009", p.getPostalAddress().getPostalZone());
      assertEquals("FR", p.getPostalAddress().getCountry().getIdentificationCode().getValue());
    }

    @Test
    @DisplayName("without a company id the tax-scheme block is omitted entirely")
    void noCompanyIdOmitsTaxScheme() {
      Party p = PartyMapper.toSupplier(null, "EUROCLEAR").getParty();
      assertTrue(p.getPartyTaxScheme() == null || p.getPartyTaxScheme().isEmpty());
      assertNull(p.getPartyLegalEntity().getCompanyId());
      assertEquals("EUROCLEAR", p.getPartyLegalEntity().getRegistrationName());
    }

    @Test
    @DisplayName("without a name the registration name is left unset")
    void noNameLeavesRegistrationNameUnset() {
      Party p = PartyMapper.toCustomer("552120222", null).getParty();
      assertNull(p.getPartyLegalEntity().getRegistrationName());
      assertEquals("552120222", p.getPartyLegalEntity().getCompanyId().getValue());
    }

    @Test
    @DisplayName("registration numbers are read back off both sides")
    void extractRegistrationNumbers() {
      assertEquals("784608416", PartyMapper.extractProviderRegistrationNumber(
          PartyMapper.toSupplier("784608416", "EUROCLEAR")));
      assertEquals("552120222", PartyMapper.extractSgEntityRegistrationNumber(
          PartyMapper.toCustomer("552120222", "SG")));
    }

    @Test
    @DisplayName("extraction tolerates every absent level of the party graph")
    void extractionIsNullSafe() {
      assertNull(PartyMapper.extractProviderRegistrationNumber(null));
      assertNull(PartyMapper.extractSgEntityRegistrationNumber(null));

      assertNull(PartyMapper.extractProviderRegistrationNumber(new AccountingSupplierParty()));
      assertNull(PartyMapper.extractSgEntityRegistrationNumber(new AccountingCustomerParty()));

      AccountingSupplierParty noLegalEntity = new AccountingSupplierParty();
      noLegalEntity.setParty(new Party());
      assertNull(PartyMapper.extractProviderRegistrationNumber(noLegalEntity));

      AccountingCustomerParty customerNoLegalEntity = new AccountingCustomerParty();
      customerNoLegalEntity.setParty(new Party());
      assertNull(PartyMapper.extractSgEntityRegistrationNumber(customerNoLegalEntity));

      AccountingSupplierParty noCompanyId = new AccountingSupplierParty();
      Party party = new Party();
      party.setPartyLegalEntity(new PartyLegalEntity());
      noCompanyId.setParty(party);
      assertNull(PartyMapper.extractProviderRegistrationNumber(noCompanyId));

      AccountingCustomerParty customerNoCompanyId = new AccountingCustomerParty();
      Party customerParty = new Party();
      customerParty.setPartyLegalEntity(new PartyLegalEntity());
      customerNoCompanyId.setParty(customerParty);
      assertNull(PartyMapper.extractSgEntityRegistrationNumber(customerNoCompanyId));
    }
  }

  // ── LineItemMapper ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("LineItemMapper")
  class Lines {

    private InvoiceItem item(String feeType, String groupingKey, String nature,
                             BigDecimal amount, BigDecimal qty, String currency) {
      InvoiceItem i = new InvoiceItem();
      i.setFeeType(feeType);
      i.setGroupingKey(groupingKey);
      i.setNatureOfExpense(nature);
      i.setFeeAmount(amount);
      i.setNotionQuantity(qty);
      i.setFeeCurrency(currency);
      return i;
    }

    @Test
    @DisplayName("with no items a single synthetic line keeps the UBL valid")
    void emptyItemsProduceOneSyntheticLine() {
      List<InvoiceLine> lines =
          LineItemMapper.toInvoiceLines(List.of(), "EUR", new BigDecimal("100.00"), "REF-1");

      assertEquals(1, lines.size());
      assertEquals("1", lines.get(0).getId());
      assertEquals("REF-1", lines.get(0).getItem().getName());
      assertEquals(new BigDecimal("100.00"), lines.get(0).getLineExtensionAmount().getValue());
      assertEquals(BigDecimal.ONE, lines.get(0).getInvoicedQuantity().getValue());
      assertEquals("C62", lines.get(0).getInvoicedQuantity().getUnitCode());
    }

    @Test
    @DisplayName("a null item list is treated the same as an empty one")
    void nullItemsProduceOneSyntheticLine() {
      assertEquals(1,
          LineItemMapper.toInvoiceLines(null, "EUR", new BigDecimal("10"), "REF").size());
    }

    @Test
    @DisplayName("the synthetic line falls back to a generic label and zero")
    void syntheticLineFallbacks() {
      List<InvoiceLine> lines = LineItemMapper.toInvoiceLines(null, "EUR", null, null);
      assertEquals("Invoice line", lines.get(0).getItem().getName());
      assertEquals(BigDecimal.ZERO, lines.get(0).getLineExtensionAmount().getValue());
      assertEquals(BigDecimal.ZERO, lines.get(0).getPrice().getPriceAmount().getValue());
    }

    @Test
    @DisplayName("lines are numbered from 1 and carry their own currency")
    void linesAreNumbered() {
      List<InvoiceLine> lines = LineItemMapper.toInvoiceLines(List.of(
          item("FEE", null, null, new BigDecimal("10.00"), BigDecimal.ONE, "USD"),
          item("FEE", null, null, new BigDecimal("20.00"), BigDecimal.ONE, null)),
          "EUR", null, null);

      assertEquals("1", lines.get(0).getId());
      assertEquals("2", lines.get(1).getId());
      assertEquals("USD", lines.get(0).getLineExtensionAmount().getCurrencyID());
      assertEquals("EUR", lines.get(1).getLineExtensionAmount().getCurrencyID(),
          "a line without its own currency inherits the invoice's");
    }

    @ParameterizedTest(name = "label resolves to [{4}]")
    @CsvSource({
        "TRADING, EQUITY,  EXPENSE, 1, TRADING EQUITY",
        "TRADING, ,        EXPENSE, 1, TRADING",
        ",        EQUITY,  EXPENSE, 1, EQUITY",
        ",        ,        EXPENSE, 1, EXPENSE",
    })
    @DisplayName("the item label falls back through fee type, grouping key then nature")
    void labelFallbackChain(String feeType, String groupingKey, String nature,
                            String qty, String expected) {
      List<InvoiceLine> lines = LineItemMapper.toInvoiceLines(
          List.of(item(feeType, groupingKey, nature, new BigDecimal("10"),
              new BigDecimal(qty), "EUR")), "EUR", null, null);
      assertEquals(expected, lines.get(0).getItem().getName());
    }

    @Test
    @DisplayName("with nothing to name it, the line is labelled by its index")
    void labelFallsBackToIndex() {
      List<InvoiceLine> lines = LineItemMapper.toInvoiceLines(
          List.of(item(null, null, null, new BigDecimal("10"), BigDecimal.ONE, "EUR")),
          "EUR", null, null);
      assertEquals("Line 1", lines.get(0).getItem().getName());
    }

    @Test
    @DisplayName("the unit price is the fee divided by the quantity")
    void unitPriceIsDivided() {
      List<InvoiceLine> lines = LineItemMapper.toInvoiceLines(
          List.of(item("FEE", null, null, new BigDecimal("100.00"), new BigDecimal("4"), "EUR")),
          "EUR", null, null);
      assertEquals(0, new BigDecimal("25.000000")
          .compareTo(lines.get(0).getPrice().getPriceAmount().getValue()));
    }

    @Test
    @DisplayName("a zero quantity cannot divide, so the fee is used as the price")
    void zeroQuantityFallsBackToTheFee() {
      List<InvoiceLine> lines = LineItemMapper.toInvoiceLines(
          List.of(item("FEE", null, null, new BigDecimal("100.00"), BigDecimal.ZERO, "EUR")),
          "EUR", null, null);
      assertEquals(new BigDecimal("100.00"),
          lines.get(0).getPrice().getPriceAmount().getValue());
    }

    @Test
    @DisplayName("absent amount and quantity default to zero and one")
    void absentAmountAndQuantityDefault() {
      List<InvoiceLine> lines = LineItemMapper.toInvoiceLines(
          List.of(item("FEE", null, null, null, null, "EUR")), "EUR", null, null);
      assertEquals(BigDecimal.ZERO, lines.get(0).getLineExtensionAmount().getValue());
      assertEquals(BigDecimal.ONE, lines.get(0).getInvoicedQuantity().getValue());
    }

    @Test
    @DisplayName("inbound, each UBL line becomes one item with a fresh id")
    void inboundLinesBecomeItems() {
      InvoiceLine line = new InvoiceLine();
      CurrencyAmount lea = new CurrencyAmount();
      lea.setValue(new BigDecimal("50.00"));
      lea.setCurrencyID("EUR");
      line.setLineExtensionAmount(lea);
      Item it = new Item();
      it.setName("CUSTODY FEE");
      line.setItem(it);
      Quantity q = new Quantity();
      q.setValue(new BigDecimal("2"));
      line.setInvoicedQuantity(q);

      List<InvoiceItem> items = LineItemMapper.toInvoiceItems(List.of(line));

      assertEquals(1, items.size());
      assertNotNull(items.get(0).getInvoiceItemId());
      assertNull(items.get(0).getInvReferenceSg(),
          "left for the store, which stamps the reference it mints from the sequence");
      assertEquals(new BigDecimal("50.00"), items.get(0).getFeeAmount());
      assertEquals("EUR", items.get(0).getFeeCurrency());
      assertEquals("CUSTODY FEE", items.get(0).getItemDescription(),
          "the line's Item.name is the supplier's free text, so it lands on the description");
      assertNull(items.get(0).getFeeType(),
          "and nowhere else — feeType is a taxonomy value owned by the fee referential, not "
              + "whatever the sender happened to type");
      assertEquals(new BigDecimal("2"), items.get(0).getNotionQuantity());
    }

    @Test
    @DisplayName("inbound tolerates a bare line and skips nulls in the list")
    void inboundIsNullSafe() {
      assertTrue(LineItemMapper.toInvoiceItems(null).isEmpty());

      List<InvoiceLine> withNull = new java.util.ArrayList<>();
      withNull.add(null);
      withNull.add(new InvoiceLine());
      List<InvoiceItem> items = LineItemMapper.toInvoiceItems(withNull);

      assertEquals(1, items.size(), "the null entry is skipped, the bare line still maps");
      assertNull(items.get(0).getFeeAmount());
      assertNull(items.get(0).getItemDescription());
      assertNull(items.get(0).getNotionQuantity());
    }

    @Test
    @DisplayName("summing line extensions tolerates nulls throughout")
    void sumLineExtensions() {
      assertEquals(BigDecimal.ZERO, LineItemMapper.sumLineExtensions(null));
      assertEquals(BigDecimal.ZERO, LineItemMapper.sumLineExtensions(List.of()));

      List<InvoiceItem> items = new java.util.ArrayList<>();
      items.add(item("A", null, null, new BigDecimal("10.00"), null, "EUR"));
      items.add(null);
      items.add(item("B", null, null, null, null, "EUR"));
      items.add(item("C", null, null, new BigDecimal("5.50"), null, "EUR"));

      assertEquals(new BigDecimal("15.50"), LineItemMapper.sumLineExtensions(items));
    }
  }

  // ── DocumentReferenceMapper ───────────────────────────────────────────────

  @Nested
  @DisplayName("DocumentReferenceMapper")
  class Documents {

    private final byte[] pdf = "%PDF-1.4\n".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("the PDF is index 0 and the spreadsheet index 1")
    void orderingIsFixed() {
      List<AdditionalDocumentReference> refs =
          DocumentReferenceMapper.toAdditionalDocumentReferences(
              new AttachmentPayload("pdf-1", pdf, "invoice.pdf", "application/pdf"),
              new AttachmentPayload("xls-1", pdf, "trades.xlsx", null));

      assertEquals(2, refs.size());
      assertEquals("pdf-1", refs.get(0).getId().getValue());
      assertEquals("xls-1", refs.get(1).getId().getValue());
      assertEquals("invoice.pdf",
          refs.get(0).getAttachment().getEmbeddedDocumentBinaryObject().getFilename());
    }

    @Test
    @DisplayName("content is base64-encoded into the embedded object")
    void contentIsBase64Encoded() {
      List<AdditionalDocumentReference> refs =
          DocumentReferenceMapper.toAdditionalDocumentReferences(
              new AttachmentPayload("pdf-1", pdf, "invoice.pdf", "application/pdf"), null);

      assertEquals(java.util.Base64.getEncoder().encodeToString(pdf),
          refs.get(0).getAttachment().getEmbeddedDocumentBinaryObject().getFile());
      assertTrue(refs.get(0).getDocumentDescription().contains("pdf-1"));
    }

    @Test
    @DisplayName("a missing filename or mime type is synthesised per slot")
    void fallbacksPerSlot() {
      List<AdditionalDocumentReference> refs =
          DocumentReferenceMapper.toAdditionalDocumentReferences(
              new AttachmentPayload("pdf-1", pdf, null, null),
              new AttachmentPayload("xls-1", pdf, null, null));

      assertEquals("pdf-1.pdf",
          refs.get(0).getAttachment().getEmbeddedDocumentBinaryObject().getFilename());
      assertEquals("application/pdf",
          refs.get(0).getAttachment().getEmbeddedDocumentBinaryObject().getMimeCode());
      assertEquals("xls-1.xlsx",
          refs.get(1).getAttachment().getEmbeddedDocumentBinaryObject().getFilename());
      assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          refs.get(1).getAttachment().getEmbeddedDocumentBinaryObject().getMimeCode());
    }

    @Test
    @DisplayName("either slot may be absent, including both")
    void slotsAreOptional() {
      assertTrue(DocumentReferenceMapper.toAdditionalDocumentReferences(null, null).isEmpty());
      assertEquals(1, DocumentReferenceMapper.toAdditionalDocumentReferences(
          new AttachmentPayload("pdf-1", pdf, "a.pdf", "application/pdf"), null).size());
      assertEquals(1, DocumentReferenceMapper.toAdditionalDocumentReferences(
          null, new AttachmentPayload("xls-1", pdf, "a.xlsx", null)).size());
    }
  }

  // ── Exceptions and the service facade ─────────────────────────────────────

  @Nested
  @DisplayName("exceptions and the facade")
  class Facade {

    @Test
    @DisplayName("the mapping exception carries a message and an optional cause")
    void mappingException() {
      EInvoiceMappingException plain = new EInvoiceMappingException("broke");
      assertEquals("broke", plain.getMessage());
      assertNull(plain.getCause());

      Exception cause = new IllegalStateException("root");
      EInvoiceMappingException wrapped = new EInvoiceMappingException("broke", cause);
      assertSame(cause, wrapped.getCause());
    }

    @Test
    @DisplayName("the fee-type resolution exception carries its reason")
    void feeTypeException() {
      assertEquals("why", new FeeTypeMatcher.FeeTypeResolutionException("why").getMessage());
    }

    @Test
    @DisplayName("the service delegates both directions and demands its collaborators")
    void serviceDelegates() {
      EInvoiceFacadeMapper facade =
          new EInvoiceFacadeMapper(TestLookups.alwaysFinds());
      MultipartExtractionService extractor = new MultipartExtractionService();
      EInvoiceMappingService service = new EInvoiceMappingService(facade, extractor);

      assertNull(service.toEInvoice(null, List.of(), null, null));
      assertNull(service.toInvoicePayable(null).model());
      assertTrue(service.extractAttachments(new Invoice()).isEmpty());

      assertThrows(NullPointerException.class, () -> new EInvoiceMappingService(null, extractor));
      assertThrows(NullPointerException.class, () -> new EInvoiceMappingService(facade, null));
    }
  }
}
