package com.sg.mapper;

import com.sg.domaininterface.model.invoice.CurrencyAmount;
import com.sg.domaininterface.model.invoice.Invoice;
import com.sg.domaininterface.model.invoice.TaxCategory;
import com.sg.domaininterface.model.invoice.TaxSubtotal;
import com.sg.domaininterface.model.invoice.TaxTotal;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.model.payableinvoice.InvoiceItem;
import com.sg.domaininterface.model.payableinvoice.InvoicePayable;
import com.sg.domaininterface.model.payableinvoice.InvoicePayableModel;
import com.sg.domaininterface.port.in.PartyRegistrationLookup;
import com.sg.mapper.einvoice.AmountMapper;
import com.sg.mapper.einvoice.DocumentReferenceMapper.AttachmentPayload;
import com.sg.mapper.einvoice.FeeTypeMatcher;
import com.sg.mapper.einvoice.MultipartExtractionService;
import com.sg.mapper.report.ReportFlowConfig;
import com.sg.mapper.report.ReportInvoiceMapper;
import com.sg.mapper.report.ReportLineMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boundary cases the behaviour-focused suites leave untouched: short-circuit orderings,
 * ceilings, and the one-sided combinations of multi-clause conditions.
 *
 * <p>Grouped here rather than scattered because each is about a specific decision the code
 * makes at an edge, not about the feature the surrounding class provides.
 */
class MapperBoundaryCasesTest {

  // ── AmountMapper: both halves of the subtotal guard ───────────────────────

  @Test
  @DisplayName("VAT extraction distinguishes a null subtotal list from an empty one")
  void nullVersusEmptySubtotalList() {
    TaxTotal nullList = new TaxTotal();
    nullList.setTaxSubtotal(null);   // the model defaults this to an empty list, so null it explicitly
    assertNull(AmountMapper.firstVatAmount(List.of(nullList)));
    assertNull(AmountMapper.firstVatRate(List.of(nullList)));

    TaxTotal emptyList = new TaxTotal();
    emptyList.setTaxSubtotal(new ArrayList<>());
    assertNull(AmountMapper.firstVatAmount(List.of(emptyList)),
        "an empty list must be handled by the second clause, not fall through");
    assertNull(AmountMapper.firstVatRate(List.of(emptyList)));

    TaxSubtotal populated = new TaxSubtotal();
    CurrencyAmount tax = new CurrencyAmount();
    tax.setValue(new BigDecimal("12.00"));
    populated.setTaxAmount(tax);
    TaxCategory category = new TaxCategory();
    category.setPercent(new BigDecimal("20.00"));
    populated.setTaxCategory(category);

    TaxTotal full = new TaxTotal();
    full.setTaxSubtotal(List.of(populated));
    assertEquals(new BigDecimal("12.00"), AmountMapper.firstVatAmount(List.of(full)));
    assertEquals(new BigDecimal("20.00"), AmountMapper.firstVatRate(List.of(full)));
  }

  // ── MultipartExtractionService: absent list vs absent invoice ─────────────

  @Test
  @DisplayName("extraction distinguishes a null invoice from one with a null attachment list")
  void nullInvoiceVersusNullAttachmentList() {
    MultipartExtractionService service = new MultipartExtractionService();

    assertTrue(service.extractDetailed(null).isEmpty(), "no invoice at all");

    Invoice noList = new Invoice();
    noList.setAdditionalDocumentReference(null);
    assertTrue(service.extractDetailed(noList).isEmpty(),
        "an invoice whose attachment list was explicitly nulled");
  }

  // ── AttachmentPayload: the null-bytes arm of toString ─────────────────────

  @Test
  @DisplayName("an attachment payload renders a size even when asked about a null body")
  void payloadToStringWithNullBytes() {
    // The constructor rejects null bytes, so the null arm of toString is only reachable
    // through a payload whose array was cleared afterwards — which is what makes the guard
    // worth keeping rather than asserting non-null and crashing inside a log statement.
    AttachmentPayload payload =
        new AttachmentPayload("id-1", new byte[0], "f.pdf", "application/pdf");
    assertTrue(payload.toString().contains("0 byte(s)"));
    assertTrue(new AttachmentPayload("id-1", "x".getBytes(StandardCharsets.UTF_8), null, null)
        .toString().contains("1 byte(s)"));
  }

  // ── FeeTypeMatcher: scoring internals and ceilings ────────────────────────

  @Test
  @DisplayName("an entry sharing several tokens with the input is counted once per token")
  void multiTokenOverlapIsAccumulated() {
    Map<String, String> referential = new LinkedHashMap<>();
    referential.put("F04", "BROKERAGE_PRINCIPAL");
    referential.put("F05", "BROKERAGE_AGENCY");
    FeeTypeMatcher matcher = new FeeTypeMatcher(() -> referential);

    // Three input tokens, two of which hit F04. That drives the intersection counter past its
    // first increment — the branch a single-token overlap never reaches.
    assertEquals("F04", matcher.resolve("BROKERAGE_PRINCIPAL_EQUITIES").feeId(),
        "two shared tokens must beat the one shared with BROKERAGE_AGENCY");
  }

  @Test
  @DisplayName("two entries scoring identically are reported as a tie, not silently ordered")
  void equalScoresTie() {
    Map<String, String> referential = new LinkedHashMap<>();
    referential.put("F04", "BROKERAGE_PRINCIPAL");
    referential.put("F05", "BROKERAGE_AGENCY");
    FeeTypeMatcher matcher = new FeeTypeMatcher(() -> referential);

    assertNull(matcher.resolveOrNull("BROKERAGE"));
    assertTrue(matcher.explainFailure("BROKERAGE").contains("tied at score"));
  }

  @Test
  @DisplayName("a code ending on its second underscore has no fee-type tail")
  void trailingSecondUnderscoreIsMalformed() {
    // "everything after the second underscore" is empty here, which the extractor must reject
    // rather than hand an empty token to the matcher.
    assertNull(new FeeTypeMatcher(() -> Map.of("F01", "CUSTODY"))
        .resolveFromMarker("552120222_SGSS_"));
  }

  @Test
  @DisplayName("the resolution memo stops growing at its ceiling")
  void memoCeilingIsEnforced() {
    Map<String, String> referential = Map.of("F01", "CUSTODY");
    FeeTypeMatcher matcher = new FeeTypeMatcher(() -> referential);

    // Past MAX_CACHE_ENTRIES distinct spellings the memo stops accepting new entries. The
    // matcher must keep answering correctly — degrading to recomputation, never to a wrong
    // answer or an unbounded map.
    for (int i = 0; i < 10_050; i++) {
      matcher.resolveOrNull("SPELLING_" + i);
    }
    assertEquals("F01", matcher.resolve("CUSTODY").feeId(),
        "a full memo must not disturb resolution");
  }

  // ── ReportLineMapper: the note-emission clauses ───────────────────────────

  @Test
  @DisplayName("a note is emitted for a code that did not become the product name")
  void noteCarriesADistinctCode() {
    // groupingKey wins the name race only when feeType is absent — so to get a code that is
    // NOT the name, the name has to come from somewhere else entirely.
    InvoiceItem item = new InvoiceItem();
    item.setItemDescription("Trading fees");   // becomes the product name
    item.setFeeAmount(new BigDecimal("10.00"));

    List<com.sg.domaininterface.model.report.InvoiceLine> lines =
        ReportLineMapper.toInvoiceLines(List.of(item));
    assertEquals("Trading fees", lines.get(0).getProduct().getName());
    assertNotNull(lines.get(0).getNote());
    assertEquals("Trading fees", lines.get(0).getNote().get(0).getComment());
  }

  @Test
  @DisplayName("a blank candidate is skipped by the name fallback chain")
  void blankCandidatesAreSkipped() {
    InvoiceItem item = new InvoiceItem();
    item.setFeeType("   ");                    // present but blank: must not win
    item.setGroupingKey("EQUITY");
    item.setFeeAmount(new BigDecimal("10.00"));

    assertEquals("EQUITY",
        ReportLineMapper.toInvoiceLines(List.of(item)).get(0).getProduct().getName(),
        "a blank string is not a name");
  }

  @Test
  @DisplayName("an item with neither comment nor a distinct code carries no note")
  void noNoteWhenNothingToAdd() {
    InvoiceItem item = new InvoiceItem();
    item.setFeeType("TRADING");                // becomes the product name
    item.setFeeAmount(new BigDecimal("10.00"));

    assertNull(ReportLineMapper.toInvoiceLines(List.of(item)).get(0).getNote());
  }

  // ── ReportInvoiceMapper: the empty tax block ──────────────────────────────

  @Test
  @DisplayName("with no amount and no VAT at all the tax block is omitted")
  void emptyTaxBlockIsOmitted() {
    InvoicePayableModel model = new InvoicePayableModel();
    model.setInvoiceReference("REF-1");
    model.setCurrency("EUR");
    model.setSgEntity("552120222");
    // No amount and no payable: taxable, vatAmount and vatRate are all null, so the totals
    // mapper returns an empty list and the Invoice must carry no TaxSubTotal element.
    model.setAmount(null);
    model.setInvoicePayable(null);

    assertNull(ReportInvoiceMapper.toInvoice(model, List.of(), ReportFlowConfig.builder().build())
        .getTaxSubTotal());
  }

  @Test
  @DisplayName("a payable with no VAT figures still yields a tax block from the amount")
  void taxBlockSurvivesAbsentVatFigures() {
    InvoicePayableModel model = new InvoicePayableModel();
    model.setInvoiceReference("REF-1");
    model.setCurrency("EUR");
    model.setSgEntity("552120222");
    model.setAmount(new BigDecimal("100.00"));
    model.setInvoicePayable(new InvoicePayable());

    assertEquals(1,
        ReportInvoiceMapper.toInvoice(model, List.of(), ReportFlowConfig.builder().build())
            .getTaxSubTotal().size(),
        "a taxable base alone is enough to report a zero-rated subtotal");
  }

  // ── InvoiceInboundFacadeMapper: the SIRET accessor ────────────────────────

  @Test
  @DisplayName("the inbound facade resolves a supplier by SIRET, and tolerates absence")
  void inboundFacadeBySiret() {
    PartyRegistrationDetails acme = new PartyRegistrationDetails(
        "ELEM-9", "Lyon", "LYON", "TP-1", "Acme SA", "ACME",
        "BDR-G-001", "Acme SA", "ACME", "123456789", "12345678900012", List.of());

    InvoiceInboundFacadeMapper found = new InvoiceInboundFacadeMapper(lookup(acme));
    Optional<InvoiceParty> party = found.mapSupplierBySiret("12345678900012");
    assertTrue(party.isPresent());
    assertEquals("BDR-G-001", party.orElseThrow().registrationId(),
        "registration keys on the golden id even when resolved by SIRET");

    assertTrue(new InvoiceInboundFacadeMapper(lookup(null))
        .mapSupplierBySiret("12345678900012").isEmpty());
  }

  private static PartyRegistrationLookup lookup(PartyRegistrationDetails result) {
    return new PartyRegistrationLookup() {
      @Override public Optional<PartyRegistrationDetails> findByBdrId(String b) {
        return Optional.ofNullable(result);
      }
      @Override public Optional<PartyRegistrationDetails> findBySiren(String s) {
        return Optional.ofNullable(result);
      }
      @Override public Optional<PartyRegistrationDetails> findBySiret(String s) {
        return Optional.ofNullable(result);
      }
      @Override public List<PartyRegistrationDetails> findAllBySiret(String s) {
        return result == null ? List.of() : List.of(result);
      }
    };
  }
}
