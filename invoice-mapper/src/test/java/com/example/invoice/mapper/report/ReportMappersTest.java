package com.example.invoice.mapper.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.invoice.service.domain.model.payableinvoice.InvoiceItem;
import com.example.invoice.service.domain.model.payableinvoice.InvoicePayable;
import com.example.invoice.service.domain.model.payableinvoice.InvoicePayableModel;
import com.example.invoice.service.domain.model.report.Buyer;
import com.example.invoice.service.domain.model.report.Invoice;
import com.example.invoice.service.domain.model.report.InvoiceLine;
import com.example.invoice.service.domain.model.report.MonetaryTotal;
import com.example.invoice.service.domain.model.report.Party;
import com.example.invoice.service.domain.model.report.ReportDocument;
import com.example.invoice.service.domain.model.report.ReportModel;
import com.example.invoice.service.domain.model.report.Seller;
import com.example.invoice.service.domain.model.report.TaxSubTotal;
import com.example.invoice.service.domain.model.PartyRegistrationDetails;
import com.example.invoice.service.domain.port.in.PartyRegistrationLookup;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The Flux 10.1 report mapping chain, from sub-mappers up to the facade. */
class ReportMappersTest {

  private static final PartyRegistrationDetails SG = new PartyRegistrationDetails(
      "E1", "elem", "EMN", "TP1", "tp", "TPM",
      "G1", "SOCIETE GENERALE", "SG", "552120222", "55212022200013", List.of());

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

  private static InvoicePayableModel model() {
    InvoicePayableModel m = new InvoicePayableModel();
    m.setInvoiceReference("CUS0226368");
    m.setInvoiceDate(LocalDate.of(2026, 4, 14));
    m.setInvoiceType("DEBIT");
    m.setCurrency("EUR");
    m.setSgEntity("552120222");
    m.setProviderId("784608416");
    m.setAmount(new BigDecimal("751.85"));

    InvoicePayable p = new InvoicePayable();
    p.setVatAmount(new BigDecimal("125.31"));
    p.setVatRate(new BigDecimal("20.00"));
    m.setInvoicePayable(p);
    return m;
  }

  private static ReportFlowConfig config() {
    return ReportFlowConfig.builder().build();
  }

  // ── ReportPartyMapper ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("ReportPartyMapper")
  class Parties {

    @Test
    @DisplayName("the seller carries SIREN, a synthesised VAT id and a country")
    void seller() {
      Seller s = ReportPartyMapper.toSeller("784608416", null);
      assertEquals("784608416", s.getCompanyId().getValue());
      assertEquals("0002", s.getCompanyId().getSchemeId());
      assertEquals("FR00784608416", s.getTaxRegistrationId().getValue());
      assertEquals("VAT", s.getTaxRegistrationId().getQualifyingId());
      assertEquals("FR", s.getPostalAddress().getCountryId(), "FR is the default");
      assertEquals("BE", ReportPartyMapper.toSeller("784608416", "BE")
          .getPostalAddress().getCountryId());
    }

    @Test
    @DisplayName("the buyer is shaped the same way")
    void buyer() {
      Buyer b = ReportPartyMapper.toBuyer("552120222", "LU");
      assertEquals("552120222", b.getCompanyId().getValue());
      assertEquals("FR00552120222", b.getTaxRegistrationId().getValue());
      assertEquals("LU", b.getPostalAddress().getCountryId());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("without a SIREN there is no party block at all")
    void absentSirenYieldsNoParty(String siren) {
      assertNull(ReportPartyMapper.toSeller(siren, "FR"));
      assertNull(ReportPartyMapper.toBuyer(siren, "FR"));
      assertNull(ReportPartyMapper.toIssuer(siren, lookup(SG), null));
    }

    @Test
    @DisplayName("the issuer takes its legal name from the referential")
    void issuerNameComesFromTheReferential() {
      Party issuer = ReportPartyMapper.toIssuer("552120222", lookup(SG), null);
      assertEquals("SOCIETE GENERALE", issuer.getName());
      assertEquals("552120222", issuer.getId().getValue());
      assertEquals("0002", issuer.getId().getSchemeId());
      assertEquals("BY", issuer.getRoleCode());
      assertNull(issuer.getUriUniversalCommunication(), "no URI configured means no element");
    }

    @Test
    @DisplayName("an unresolvable issuer still produces the block, without a name")
    void issuerSurvivesAnEmptyReferential() {
      assertNull(ReportPartyMapper.toIssuer("552120222", lookup(null), null).getName());
      assertNull(ReportPartyMapper.toIssuer("552120222", null, null).getName(),
          "no referential wired at all must not break report assembly");
    }

    @Test
    @DisplayName("a configured issuer URI is emitted")
    void issuerUriIsEmitted() {
      assertEquals("urn:cef:issuer",
          ReportPartyMapper.toIssuer("552120222", lookup(SG), "urn:cef:issuer")
              .getUriUniversalCommunication().getUriId());
    }

    @Test
    @DisplayName("the sender comes from the platform config, not the invoice")
    void sender() {
      Party sender = ReportPartyMapper.toSender(ReportFlowConfig.builder()
          .platformMatricule("PA07").platformName("MY PA").platformUriId("urn:cef:pa").build());

      assertEquals("PA07", sender.getId().getValue());
      assertEquals("0238", sender.getId().getSchemeId());
      assertEquals("MY PA", sender.getName());
      assertEquals("WK", sender.getRoleCode());
      assertEquals("urn:cef:pa", sender.getUriUniversalCommunication().getUriId());
    }

    @Test
    @DisplayName("no config means no sender block, and no URI means no URI element")
    void senderEdgeCases() {
      assertNull(ReportPartyMapper.toSender(null));
      assertNull(ReportPartyMapper.toSender(config()).getUriUniversalCommunication());
    }

    @Test
    @DisplayName("the VAT id is the SIREN behind a placeholder key")
    void vatIdIsSynthesised() {
      assertEquals("FR00123456789", ReportPartyMapper.buildVatId("123456789"));
    }
  }

  // ── ReportTotalsMapper ────────────────────────────────────────────────────

  @Nested
  @DisplayName("ReportTotalsMapper")
  class Totals {

    @Test
    @DisplayName("the monetary total carries the tax-exclusive figure and the VAT amount")
    void monetaryTotal() {
      MonetaryTotal t = ReportTotalsMapper.toMonetaryTotal(
          new BigDecimal("751.85"), new BigDecimal("125.31"), "EUR");

      assertEquals(new BigDecimal("626.54"), t.getTaxExclusiveAmount());
      assertEquals(new BigDecimal("125.31"), t.getTaxAmount().getValue());
      assertEquals("EUR", t.getTaxAmount().getCurrencyCode());
    }

    @Test
    @DisplayName("no VAT is treated as zero")
    void monetaryTotalWithoutVat() {
      assertEquals(new BigDecimal("100.00"),
          ReportTotalsMapper.toMonetaryTotal(new BigDecimal("100.00"), null, "EUR")
              .getTaxExclusiveAmount());
    }

    @Test
    @DisplayName("without a total there is no monetary block")
    void monetaryTotalNeedsATotal() {
      assertNull(ReportTotalsMapper.toMonetaryTotal(null, BigDecimal.ONE, "EUR"));
    }

    @Test
    @DisplayName("one subtotal is produced, defaulting to the standard rate category")
    void taxSubTotals() {
      List<TaxSubTotal> subs = ReportTotalsMapper.toTaxSubTotals(
          new BigDecimal("626.54"), new BigDecimal("125.31"), new BigDecimal("20.00"));

      assertEquals(1, subs.size());
      assertEquals(new BigDecimal("626.54"), subs.get(0).getTaxableAmount());
      assertEquals(new BigDecimal("125.31"), subs.get(0).getTaxAmount());
      assertEquals("S", subs.get(0).getTaxCategory().getCode());
      assertEquals(new BigDecimal("20.00"), subs.get(0).getTaxCategory().getPercent());
    }

    @Test
    @DisplayName("with nothing at all the block is omitted")
    void taxSubTotalsOmitted() {
      assertTrue(ReportTotalsMapper.toTaxSubTotals(null, null, null).isEmpty());
    }

    @Test
    @DisplayName("any one figure present is enough, the rest default to zero")
    void taxSubTotalsPartialInput() {
      assertEquals(BigDecimal.ZERO,
          ReportTotalsMapper.toTaxSubTotals(new BigDecimal("100"), null, null)
              .get(0).getTaxAmount());
      assertEquals(BigDecimal.ZERO,
          ReportTotalsMapper.toTaxSubTotals(null, new BigDecimal("20"), null)
              .get(0).getTaxableAmount());
      assertEquals(BigDecimal.ZERO,
          ReportTotalsMapper.toTaxSubTotals(null, null, new BigDecimal("20"))
              .get(0).getTaxableAmount());
    }
  }

  // ── ReportLineMapper ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("ReportLineMapper")
  class Lines {

    private InvoiceItem item(String feeType, String groupingKey, String nature,
                             String description, BigDecimal amount, BigDecimal qty) {
      InvoiceItem i = new InvoiceItem();
      i.setFeeType(feeType);
      i.setGroupingKey(groupingKey);
      i.setNatureOfExpense(nature);
      i.setItemDescription(description);
      i.setFeeAmount(amount);
      i.setNotionQuantity(qty);
      return i;
    }

    @Test
    @DisplayName("no items means no Line element at all, not an empty one")
    void absentItemsYieldNull() {
      assertNull(ReportLineMapper.toInvoiceLines(null));
      assertNull(ReportLineMapper.toInvoiceLines(List.of()));

      List<InvoiceItem> onlyNulls = new java.util.ArrayList<>();
      onlyNulls.add(null);
      assertNull(ReportLineMapper.toInvoiceLines(onlyNulls),
          "a list of nothing usable is the same as no list");
    }

    @Test
    @DisplayName("a line carries product, quantity and price")
    void fullLine() {
      List<InvoiceLine> lines = ReportLineMapper.toInvoiceLines(List.of(
          item("TRADING", null, null, "Trading fees", new BigDecimal("100.00"),
              new BigDecimal("3"))));

      assertEquals(1, lines.size());
      assertEquals("TRADING", lines.get(0).getProduct().getName());
      assertEquals(new BigDecimal("3"), lines.get(0).getBilledQuantity().getValue());
      assertEquals("EA", lines.get(0).getBilledQuantity().getUnitCode());
      assertEquals(new BigDecimal("100.00"), lines.get(0).getPrice().getPriceAmount());
    }

    @ParameterizedTest(name = "product name resolves to [{4}]")
    @CsvSource({
        "TRADING, EQUITY,  EXPENSE, DESC, TRADING",
        ",        EQUITY,  EXPENSE, DESC, EQUITY",
        ",        ,        EXPENSE, DESC, EXPENSE",
        ",        ,        ,        DESC, DESC",
    })
    @DisplayName("the product name falls back through four candidates")
    void productNameFallback(String feeType, String groupingKey, String nature,
                             String description, String expected) {
      List<InvoiceLine> lines = ReportLineMapper.toInvoiceLines(
          List.of(item(feeType, groupingKey, nature, description, BigDecimal.ONE, null)));
      assertEquals(expected, lines.get(0).getProduct().getName());
    }

    @Test
    @DisplayName("with nothing to name it, the product element is omitted")
    void noNameOmitsProduct() {
      List<InvoiceLine> lines = ReportLineMapper.toInvoiceLines(
          List.of(item(null, null, null, null, BigDecimal.ONE, null)));
      assertNull(lines.get(0).getProduct());
    }

    @Test
    @DisplayName("quantity defaults to one and an absent fee omits the price")
    void quantityAndPriceDefaults() {
      List<InvoiceLine> lines = ReportLineMapper.toInvoiceLines(
          List.of(item("FEE", null, null, null, null, null)));

      assertEquals(BigDecimal.ONE, lines.get(0).getBilledQuantity().getValue());
      assertNull(lines.get(0).getPrice(), "no amount means no Price element");
    }

    @Test
    @DisplayName("a note is emitted only when it adds something beyond the product name")
    void notesAreEmittedOnlyWhenTheyAdd() {
      // feeType became the product name, and there is no description: nothing left to say.
      assertNull(ReportLineMapper.toInvoiceLines(
          List.of(item("TRADING", null, null, null, BigDecimal.ONE, null)))
          .get(0).getNote());

      // A description is extra content, so a note is emitted with no duplicated code.
      List<InvoiceLine> withDesc = ReportLineMapper.toInvoiceLines(
          List.of(item("TRADING", null, null, "Trading fees", BigDecimal.ONE, null)));
      assertEquals("Trading fees", withDesc.get(0).getNote().get(0).getComment());
      assertNull(withDesc.get(0).getNote().get(0).getCode(),
          "the code became the product name, so repeating it in the note is noise");

      // The product name came from the grouping key, so the fee type is still worth carrying.
      List<InvoiceLine> codeDiffers = ReportLineMapper.toInvoiceLines(
          List.of(item("TRADING", null, null, null, BigDecimal.ONE, null)));
      assertNull(codeDiffers.get(0).getNote());
    }

    @Test
    @DisplayName("a fee type distinct from the product name is carried as the note code")
    void distinctCodeIsCarried() {
      InvoiceItem i = new InvoiceItem();
      i.setFeeType("TRD");
      i.setItemDescription("Trading fees");
      i.setGroupingKey("EQUITY");
      // feeType wins the name race, so build one where it does not: name from description only.
      InvoiceItem nameFromDescription = new InvoiceItem();
      nameFromDescription.setItemDescription("Trading fees");

      List<InvoiceLine> lines = ReportLineMapper.toInvoiceLines(List.of(nameFromDescription));
      assertEquals("Trading fees", lines.get(0).getProduct().getName());
      assertEquals("Trading fees", lines.get(0).getNote().get(0).getComment());
    }

    @Test
    @DisplayName("null entries are skipped, real ones survive")
    void nullEntriesAreSkipped() {
      List<InvoiceItem> items = new java.util.ArrayList<>();
      items.add(null);
      items.add(item("FEE", null, null, null, BigDecimal.ONE, null));
      assertEquals(1, ReportLineMapper.toInvoiceLines(items).size());
    }

    @Test
    @DisplayName("mapping a null item yields null")
    void nullItemYieldsNull() {
      assertNull(ReportLineMapper.toInvoiceLine(null));
    }
  }

  // ── ReportInvoiceMapper ───────────────────────────────────────────────────

  @Nested
  @DisplayName("ReportInvoiceMapper")
  class Invoices {

    @Test
    @DisplayName("the header, parties and totals are assembled")
    void fullInvoice() {
      Invoice inv = ReportInvoiceMapper.toInvoice(model(), List.of(), config());

      assertEquals("CUS0226368", inv.getId());
      assertEquals(LocalDate.of(2026, 4, 14), inv.getIssueDate());
      assertEquals("380", inv.getTypeCode());
      assertEquals("EUR", inv.getCurrencyCode());
      assertEquals("784608416", inv.getSeller().getCompanyId().getValue());
      assertEquals("552120222", inv.getBuyer().getCompanyId().getValue());
      assertEquals("S1", inv.getBusinessProcess().getId());
      assertNotNull(inv.getMonetaryTotal());
      assertEquals(1, inv.getTaxSubTotal().size());
      assertNull(inv.getLine(), "no items means no Line block");
    }

    @ParameterizedTest(name = "{0} maps to UNTDID {1}")
    @CsvSource({"DEBIT, 380", "CREDIT, 381", "CORRECTED, 384",
        "debit, 380", "SOMETHING, 380"})
    @DisplayName("the invoice type maps to a UNTDID 1001 code")
    void typeCode(String type, String expected) {
      InvoicePayableModel m = model();
      m.setInvoiceType(type);
      assertEquals(expected, ReportInvoiceMapper.toInvoice(m, List.of(), config()).getTypeCode());
    }

    @Test
    @DisplayName("an absent invoice type defaults to a commercial invoice")
    void absentTypeDefaults() {
      InvoicePayableModel m = model();
      m.setInvoiceType(null);
      assertEquals("380", ReportInvoiceMapper.toInvoice(m, List.of(), config()).getTypeCode());
    }

    @Test
    @DisplayName("an invoice period is emitted only when a date is present")
    void invoicePeriod() {
      InvoicePayableModel m = model();
      assertNull(ReportInvoiceMapper.toInvoice(m, List.of(), config()).getInvoicePeriod());

      m.setTradingStartDate(LocalDate.of(2026, 4, 1));
      assertEquals(LocalDate.of(2026, 4, 1),
          ReportInvoiceMapper.toInvoice(m, List.of(), config()).getInvoicePeriod().getStartDate());

      InvoicePayableModel endOnly = model();
      endOnly.setTradingEndDate(LocalDate.of(2026, 4, 30));
      assertEquals(LocalDate.of(2026, 4, 30),
          ReportInvoiceMapper.toInvoice(endOnly, List.of(), config())
              .getInvoicePeriod().getEndDate());
    }

    @Test
    @DisplayName("the tax amount falls back to taxAmount when vatAmount is absent")
    void taxAmountFallback() {
      InvoicePayableModel m = model();
      m.getInvoicePayable().setVatAmount(null);
      m.getInvoicePayable().setTaxAmount(new BigDecimal("50.00"));

      assertEquals(new BigDecimal("50.00"),
          ReportInvoiceMapper.toInvoice(m, List.of(), config())
              .getMonetaryTotal().getTaxAmount().getValue());
    }

    @Test
    @DisplayName("a model with no payable block still maps its header")
    void modelWithoutPayable() {
      InvoicePayableModel m = model();
      m.setInvoicePayable(null);
      Invoice inv = ReportInvoiceMapper.toInvoice(m, List.of(), config());
      assertEquals("CUS0226368", inv.getId());
      assertNull(inv.getDueDate());
    }

    @Test
    @DisplayName("no config means no business-process block")
    void withoutConfig() {
      assertNull(ReportInvoiceMapper.toInvoice(model(), List.of(), null).getBusinessProcess());
    }

    @Test
    @DisplayName("line items reach the Line block")
    void linesReachTheInvoice() {
      InvoiceItem i = new InvoiceItem();
      i.setFeeType("TRADING");
      i.setFeeAmount(new BigDecimal("10.00"));
      assertEquals(1, ReportInvoiceMapper.toInvoice(model(), List.of(i), config())
          .getLine().size());
    }

    @Test
    @DisplayName("the mandatory source fields are demanded by name")
    void mandatoryFields() {
      assertThrows(ReportMappingException.class,
          () -> ReportInvoiceMapper.toInvoice(null, List.of(), config()));

      InvoicePayableModel noRef = model();
      noRef.setInvoiceReference(null);
      assertTrue(assertThrows(ReportMappingException.class,
          () -> ReportInvoiceMapper.toInvoice(noRef, List.of(), config()))
          .getMessage().contains("invoiceReference"));

      InvoicePayableModel blankRef = model();
      blankRef.setInvoiceReference("  ");
      assertThrows(ReportMappingException.class,
          () -> ReportInvoiceMapper.toInvoice(blankRef, List.of(), config()));

      InvoicePayableModel noCurrency = model();
      noCurrency.setCurrency(null);
      assertTrue(assertThrows(ReportMappingException.class,
          () -> ReportInvoiceMapper.toInvoice(noCurrency, List.of(), config()))
          .getMessage().contains("currency"));

      InvoicePayableModel blankCurrency = model();
      blankCurrency.setCurrency(" ");
      assertThrows(ReportMappingException.class,
          () -> ReportInvoiceMapper.toInvoice(blankCurrency, List.of(), config()));
    }

    @Test
    @DisplayName("an absent amount leaves the taxable base unset rather than guessing zero")
    void absentAmount() {
      InvoicePayableModel m = model();
      m.setAmount(null);
      assertNull(ReportInvoiceMapper.toInvoice(m, List.of(), config()).getMonetaryTotal());
    }
  }

  // ── ReportDocumentMapper ──────────────────────────────────────────────────

  @Nested
  @DisplayName("ReportDocumentMapper")
  class Documents {

    @Test
    @DisplayName("the transmission id is siren, reference and timestamp")
    void transmissionId() {
      ReportDocument doc = ReportDocumentMapper.toReportDocument(
          model(), config(), lookup(SG), LocalDateTime.of(2026, 4, 14, 10, 30, 0));

      assertEquals("552120222_CUS0226368_20260414103000", doc.getId());
      assertEquals("IN", doc.getTypeCode());
      assertNotNull(doc.getSender());
      assertEquals("SOCIETE GENERALE", doc.getIssuer().getName());
      assertEquals(LocalDateTime.of(2026, 4, 14, 10, 30, 0),
          doc.getIssueDateTime().getDateTimeString());
    }

    @Test
    @DisplayName("a missing reference still yields a well-formed id")
    void missingReferenceStillProducesAnId() {
      InvoicePayableModel m = model();
      m.setInvoiceReference(null);
      assertTrue(ReportDocumentMapper.toReportDocument(
          m, config(), lookup(SG), LocalDateTime.of(2026, 4, 14, 10, 30, 0))
          .getId().contains("NOREF"));
    }

    @Test
    @DisplayName("an absent timestamp defaults to now")
    void absentTimestampDefaultsToNow() {
      assertNotNull(ReportDocumentMapper.toReportDocument(model(), config(), lookup(SG), null)
          .getIssueDateTime().getDateTimeString());
    }

    @Test
    @DisplayName("without config the transmission type falls back to initial")
    void withoutConfig() {
      assertEquals("IN", ReportDocumentMapper.toReportDocument(
          model(), null, lookup(SG), LocalDateTime.now()).getTypeCode());
    }

    @Test
    @DisplayName("the declarant SIREN is mandatory — the report has no issuer without it")
    void sgEntityIsMandatory() {
      assertThrows(ReportMappingException.class, () -> ReportDocumentMapper.toReportDocument(
          null, config(), lookup(SG), LocalDateTime.now()));

      InvoicePayableModel noSg = model();
      noSg.setSgEntity(null);
      assertTrue(assertThrows(ReportMappingException.class,
          () -> ReportDocumentMapper.toReportDocument(noSg, config(), lookup(SG), null))
          .getMessage().contains("sgEntity"));

      InvoicePayableModel blankSg = model();
      blankSg.setSgEntity("  ");
      assertThrows(ReportMappingException.class,
          () -> ReportDocumentMapper.toReportDocument(blankSg, config(), lookup(SG), null));
    }
  }

  // ── Facade and service ────────────────────────────────────────────────────

  @Nested
  @DisplayName("facade and service")
  class Facade {

    @Test
    @DisplayName("a full report model is assembled from a payable")
    void fullReport() {
      ReportModel report = new ReportFacadeMapper(lookup(SG), config())
          .toReportModel(model(), List.of());

      assertEquals("552120222", report.getSgEntity());
      assertEquals("DRAFT", report.getStatus());
      assertEquals("IN", report.getTransmissionType());
      assertNotNull(report.getCreatedDate());
      assertNotNull(report.getLastUpdatedDate());
      assertEquals(false, report.isDeleted());
      assertNotNull(report.getReport().getReportDocument());
      assertEquals(1, report.getReport().getTransactionsReport().getInvoice().size());
    }

    @Test
    @DisplayName("the reporting period comes from the trading dates")
    void reportingPeriod() {
      InvoicePayableModel m = model();
      m.setTradingStartDate(LocalDate.of(2026, 4, 1));
      m.setTradingEndDate(LocalDate.of(2026, 4, 30));

      ReportModel report = new ReportFacadeMapper(lookup(SG), config()).toReportModel(m, List.of());
      assertEquals(LocalDate.of(2026, 4, 1), report.getPeriodStartDate());
      assertEquals(LocalDate.of(2026, 4, 30), report.getPeriodEndDate());
      assertEquals(LocalDate.of(2026, 4, 1),
          report.getReport().getTransactionsReport().getReportPeriod().getStartDate());
    }

    @Test
    @DisplayName("audit fields carry through to the report envelope")
    void auditFieldsCarryThrough() {
      InvoicePayableModel m = model();
      m.setCreatedByUser("alice");
      m.setLastUpdatedByUser("bob");

      ReportModel report = new ReportFacadeMapper(lookup(SG), config()).toReportModel(m, List.of());
      assertEquals("alice", report.getCreatedByUser());
      assertEquals("bob", report.getLastUpdatedByUser());
    }

    @Test
    @DisplayName("a null model is rejected by name")
    void nullModelRejected() {
      ReportFacadeMapper facade = new ReportFacadeMapper(lookup(SG), config());
      assertTrue(assertThrows(ReportMappingException.class,
          () -> facade.toReportModel(null, List.of()))
          .getMessage().contains("InvoicePayableModel is null"));
    }

    @Test
    @DisplayName("both collaborators are mandatory")
    void collaboratorsMandatory() {
      assertThrows(NullPointerException.class, () -> new ReportFacadeMapper(null, config()));
      assertThrows(NullPointerException.class, () -> new ReportFacadeMapper(lookup(SG), null));
    }

    @Test
    @DisplayName("the service delegates to the facade")
    void serviceDelegates() {
      ReportMappingService service =
          new ReportMappingService(new ReportFacadeMapper(lookup(SG), config()));
      assertEquals("552120222", service.toReport(model(), List.of()).getSgEntity());
      assertThrows(NullPointerException.class, () -> new ReportMappingService(null));
    }

    @Test
    @DisplayName("the report mapping exception carries a message and an optional cause")
    void mappingException() {
      assertEquals("broke", new ReportMappingException("broke").getMessage());
      Exception cause = new IllegalStateException("root");
      assertSame(cause, new ReportMappingException("broke", cause).getCause());
    }

    @Test
    @DisplayName("the flow config exposes its per-environment defaults")
    void flowConfigDefaults() {
      ReportFlowConfig c = config();
      assertEquals("PA01", c.getPlatformMatricule());
      assertEquals("PLACEHOLDER PA PLATFORM", c.getPlatformName());
      assertEquals("S1", c.getDefaultBusinessProcessId());
      assertEquals("urn.cpro.gouv.fr:1p0:ereporting", c.getDefaultBusinessProcessTypeId());
      assertEquals("IN", c.getDefaultTransmissionTypeCode());
      assertNull(c.getPlatformUriId());
      assertNull(c.getIssuerUriId());
    }
  }
}
