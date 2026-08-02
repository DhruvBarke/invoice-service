package com.example.invoice.mapper.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.invoice.mapper.einvoice.DocumentReferenceMapper.AttachmentPayload;
import com.example.invoice.mapper.einvoice.EInvoiceFacadeMapper.MappedResult;
import com.example.invoice.mapper.einvoice.model.invoice.CodedValue;
import com.example.invoice.mapper.einvoice.model.invoice.CurrencyAmount;
import com.example.invoice.mapper.einvoice.model.invoice.Invoice;
import com.example.invoice.mapper.einvoice.model.invoice.InvoiceLine;
import com.example.invoice.mapper.einvoice.model.invoice.Item;
import com.example.invoice.mapper.einvoice.model.invoice.LegalMonetaryTotal;
import com.example.invoice.mapper.einvoice.model.invoice.Period;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoiceItem;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoicePayable;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoicePayableModel;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Both directions of the top-level einvoice facade. */
class EInvoiceFacadeMapperTest {

  private static final byte[] PDF = "%PDF-1.4\n".getBytes(StandardCharsets.UTF_8);

  private static EInvoiceFacadeMapper mapper() {
    return new EInvoiceFacadeMapper(TestLookups.alwaysFinds());
  }

  private static InvoicePayableModel model() {
    InvoicePayableModel m = new InvoicePayableModel();
    m.setInvoiceReference("CUS0226368");
    m.setInvoiceDate(LocalDate.of(2026, 4, 14));
    m.setInvoiceType("DEBIT");
    m.setCurrency("EUR");
    m.setAmount(new BigDecimal("751.85"));
    m.setProviderId("784608416");
    m.setSgEntity("552120222");

    InvoicePayable p = new InvoicePayable();
    p.setVatAmount(new BigDecimal("125.31"));
    p.setVatRate(new BigDecimal("20.00"));
    p.setProviderName("EUROCLEAR");
    p.setSgEntityName("SOCIETE GENERALE");
    p.setProviderReference("PROV-1");
    m.setInvoicePayable(p);
    return m;
  }

  // ── Outbound ──────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("payable → einvoice")
  class Outbound {

    @Test
    @DisplayName("the UBL header, parties, totals and a line are all produced")
    void fullInvoice() {
      Invoice inv = mapper().toEInvoice(model(), List.of(), null, null);

      assertEquals("2.1", inv.getUblVersionId());
      assertEquals("urn.cpro.gouv.fr:1p0:einvoicingextract#", inv.getCustomizationId());
      assertEquals("S1", inv.getProfileId());
      assertEquals("CUS0226368", inv.getId());
      assertEquals(LocalDate.of(2026, 4, 14), inv.getIssueDate());
      assertEquals("380", inv.getInvoiceTypeCode().getValue());
      assertEquals("EUR", inv.getDocumentCurrencyCode().getValue());

      assertEquals("784608416",
          inv.getAccountingSupplierParty().getParty().getPartyLegalEntity()
              .getCompanyId().getValue());
      assertEquals("552120222",
          inv.getAccountingCustomerParty().getParty().getPartyLegalEntity()
              .getCompanyId().getValue());

      assertEquals(new BigDecimal("626.54"),
          inv.getLegalMonetaryTotal().getLineExtensionAmount().getValue());
      assertEquals(1, inv.getTaxTotal().size());
      assertEquals(1, inv.getInvoiceLine().size(),
          "with no items a synthetic line keeps the document valid");
    }

    @Test
    @DisplayName("an invoice period is emitted when either trading date is present")
    void invoicePeriodIsEmitted() {
      InvoicePayableModel start = model();
      start.setTradingStartDate(LocalDate.of(2026, 4, 1));
      Invoice withStart = mapper().toEInvoice(start, List.of(), null, null);
      assertEquals(LocalDate.of(2026, 4, 1), withStart.getInvoicePeriod().getStartDate());

      InvoicePayableModel end = model();
      end.setTradingEndDate(LocalDate.of(2026, 4, 30));
      assertEquals(LocalDate.of(2026, 4, 30),
          mapper().toEInvoice(end, List.of(), null, null).getInvoicePeriod().getEndDate());

      assertNull(mapper().toEInvoice(model(), List.of(), null, null).getInvoicePeriod(),
          "no trading dates means no period element");
    }

    @Test
    @DisplayName("line items become invoice lines")
    void itemsBecomeLines() {
      InvoiceItem i = new InvoiceItem();
      i.setFeeType("CUSTODY");
      i.setFeeAmount(new BigDecimal("626.54"));
      i.setFeeCurrency("EUR");

      Invoice inv = mapper().toEInvoice(model(), List.of(i), null, null);
      assertEquals(1, inv.getInvoiceLine().size());
      assertEquals("CUSTODY", inv.getInvoiceLine().get(0).getItem().getName());
    }

    @Test
    @DisplayName("attachments are embedded only when supplied")
    void attachmentsAreOptional() {
      assertTrue(mapper().toEInvoice(model(), List.of(), null, null)
              .getAdditionalDocumentReference().isEmpty(),
          "with no payloads the model keeps its default empty list — the mapper writes nothing");

      Invoice withPdf = mapper().toEInvoice(model(), List.of(),
          new AttachmentPayload("pdf-1", PDF, "invoice.pdf", "application/pdf"), null);
      assertEquals(1, withPdf.getAdditionalDocumentReference().size());

      Invoice withBoth = mapper().toEInvoice(model(), List.of(),
          new AttachmentPayload("pdf-1", PDF, "invoice.pdf", "application/pdf"),
          new AttachmentPayload("xls-1", PDF, "trades.xlsx", null));
      assertEquals(2, withBoth.getAdditionalDocumentReference().size());

      Invoice excelOnly = mapper().toEInvoice(model(), List.of(), null,
          new AttachmentPayload("xls-1", PDF, "trades.xlsx", null));
      assertEquals(1, excelOnly.getAdditionalDocumentReference().size());
    }

    @Test
    @DisplayName("a model with no payable block still produces a valid document")
    void modelWithoutPayable() {
      InvoicePayableModel m = model();
      m.setInvoicePayable(null);

      Invoice inv = mapper().toEInvoice(m, List.of(), null, null);
      assertNotNull(inv.getLegalMonetaryTotal());
      assertTrue(inv.getTaxTotal().isEmpty(), "no payable means no VAT figures to report");
      assertNull(inv.getAccountingSupplierParty().getParty()
          .getPartyLegalEntity().getRegistrationName());
    }

    @Test
    @DisplayName("without an amount the totals block is omitted and the line falls back")
    void modelWithoutAmount() {
      InvoicePayableModel m = model();
      m.setAmount(null);

      Invoice inv = mapper().toEInvoice(m, List.of(), null, null);
      assertNull(inv.getLegalMonetaryTotal(),
          "no total means no LegalMonetaryTotal element to hang figures on");
      assertEquals(1, inv.getInvoiceLine().size());
    }

    @Test
    @DisplayName("the synthetic line is labelled from the provider reference, then refCptyId")
    void syntheticLineLabel() {
      assertEquals("PROV-1", mapper().toEInvoice(model(), List.of(), null, null)
          .getInvoiceLine().get(0).getItem().getName());

      InvoicePayableModel noPayable = model();
      noPayable.setInvoicePayable(null);
      noPayable.setRefCptyId("CPTY-9");
      assertEquals("CPTY-9", mapper().toEInvoice(noPayable, List.of(), null, null)
          .getInvoiceLine().get(0).getItem().getName());
    }

    @Test
    @DisplayName("a null model maps to a null document")
    void nullModel() {
      assertNull(mapper().toEInvoice(null, List.of(), null, null));
    }
  }

  // ── Inbound ───────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("einvoice → payable")
  class Inbound {

    private Invoice einvoice() {
      Invoice inv = new Invoice();
      inv.setId("CUS0226368");
      inv.setIssueDate(LocalDate.of(2026, 4, 14));

      CodedValue type = new CodedValue();
      type.setValue("380");
      inv.setInvoiceTypeCode(type);

      CodedValue currency = new CodedValue();
      currency.setValue("EUR");
      inv.setDocumentCurrencyCode(currency);

      inv.setAccountingSupplierParty(PartyMapper.toSupplier("784608416", "EUROCLEAR"));
      inv.setAccountingCustomerParty(PartyMapper.toCustomer("552120222", "SG"));

      LegalMonetaryTotal totals = new LegalMonetaryTotal();
      CurrencyAmount payable = new CurrencyAmount();
      payable.setValue(new BigDecimal("751.85"));
      payable.setCurrencyID("EUR");
      totals.setPayableAmount(payable);
      inv.setLegalMonetaryTotal(totals);

      inv.setTaxTotal(AmountMapper.toTaxTotal(new BigDecimal("125.31"),
          new BigDecimal("20.00"), new BigDecimal("626.54"), "EUR"));
      return inv;
    }

    @Test
    @DisplayName("the header, amounts and party identity all come across")
    void fullMapping() {
      MappedResult result = mapper().toInvoicePayable(einvoice());
      InvoicePayableModel m = result.model();

      assertEquals("CUS0226368", m.getInvoiceReference());
      assertEquals(LocalDate.of(2026, 4, 14), m.getInvoiceDate());
      assertEquals("DEBIT", m.getInvoiceType());
      assertEquals("REGISTERED", m.getInvoiceStatus());
      assertEquals("EInvoice", m.getFeeCategory());
      assertEquals("EUR", m.getCurrency());
      assertEquals(new BigDecimal("751.85"), m.getAmount());
      assertEquals("552120222", m.getSgEntity());

      InvoicePayable p = m.getInvoicePayable();
      assertEquals(new BigDecimal("751.85"), p.getAmountIncludingTax());
      assertEquals("751.85", p.getInvoicedAmount());
      assertEquals(new BigDecimal("125.31"), p.getVatAmount());
      assertEquals(new BigDecimal("20.00"), p.getVatRate());
      assertEquals("EINV", p.getFeeCategoryCode());
      assertEquals("CUS0226368", p.getProviderReference());
    }

    @Test
    @DisplayName("party names come from the referential, not the document")
    void partyNamesComeFromTheReferential() {
      InvoicePayable p = mapper().toInvoicePayable(einvoice()).model().getInvoicePayable();
      assertEquals("Acme SA", p.getSgEntityName());
      assertEquals("Acme SA", p.getProviderName());
      assertEquals("ACME", p.getSgEntityMnemonic());
      assertEquals("ACME", p.getProviderMnemo());
      assertEquals("BDR-G-001", p.getSgEntityCode(), "the golden id is the internal identity");
    }

    @Test
    @DisplayName("fields the shared referential does not carry are left null, not invented")
    void unmappableFieldsStayNull() {
      InvoicePayable p = mapper().toInvoicePayable(einvoice()).model().getInvoicePayable();
      assertNull(p.getProviderGroup());
      assertNull(p.getLeiDetails());
    }

    @Test
    @DisplayName("attachment ids are left for the registration step to fill in")
    void attachmentIdsAreLeftUnset() {
      InvoicePayable p = mapper().toInvoicePayable(einvoice()).model().getInvoicePayable();
      assertNull(p.getInvoicePdfId());
      assertNull(p.getInvoiceExcelId());
    }

    @Test
    @DisplayName("an unresolvable party leaves the enriched fields null but still maps")
    void unresolvablePartyStillMaps() {
      EInvoiceFacadeMapper m = new EInvoiceFacadeMapper(TestLookups.findsNothing());
      InvoicePayable p = m.toInvoicePayable(einvoice()).model().getInvoicePayable();

      assertNull(p.getProviderName());
      assertNull(p.getSgEntityName());
      assertEquals("784608416", m.toInvoicePayable(einvoice()).model().getProviderId(),
          "the SIREN is the fallback identity when the referential has no entry");
    }

    @Test
    @DisplayName("the invoice period comes across when present")
    void invoicePeriodIsRead() {
      Invoice inv = einvoice();
      Period period = new Period();
      period.setStartDate(LocalDate.of(2026, 4, 1));
      period.setEndDate(LocalDate.of(2026, 4, 30));
      inv.setInvoicePeriod(period);

      InvoicePayableModel m = mapper().toInvoicePayable(inv).model();
      assertEquals(LocalDate.of(2026, 4, 1), m.getTradingStartDate());
      assertEquals(LocalDate.of(2026, 4, 30), m.getTradingEndDate());
    }

    @Test
    @DisplayName("without a totals block the amount is left unset rather than zeroed")
    void withoutTotals() {
      Invoice inv = einvoice();
      inv.setLegalMonetaryTotal(null);

      InvoicePayableModel m = mapper().toInvoicePayable(inv).model();
      assertNull(m.getAmount());
      assertNull(m.getInvoicePayable().getAmountIncludingTax());
      assertNull(m.getInvoicePayable().getInvoicedAmount(),
          "no amount means no stringified amount either");
    }

    @Test
    @DisplayName("lines become items carrying the parent reference")
    void linesBecomeItems() {
      Invoice inv = einvoice();
      InvoiceLine line = new InvoiceLine();
      CurrencyAmount lea = new CurrencyAmount();
      lea.setValue(new BigDecimal("626.54"));
      lea.setCurrencyID("EUR");
      line.setLineExtensionAmount(lea);
      Item item = new Item();
      item.setName("REGLEMENT LIVRAISON");
      line.setItem(item);
      inv.setInvoiceLine(List.of(line));

      MappedResult result = mapper().toInvoicePayable(inv);
      assertEquals(1, result.items().size());
      assertEquals("CUS0226368", result.items().get(0).getInvReferenceSg());
      assertEquals(new BigDecimal("626.54"), result.items().get(0).getFeeAmount());
    }

    @Test
    @DisplayName("a null document maps to an empty result rather than throwing")
    void nullInvoice() {
      MappedResult result = mapper().toInvoicePayable(null);
      assertNull(result.model());
      assertTrue(result.items().isEmpty());
    }

    @Test
    @DisplayName("a document with no parties still maps its header")
    void withoutParties() {
      Invoice inv = einvoice();
      inv.setAccountingSupplierParty(null);
      inv.setAccountingCustomerParty(null);

      InvoicePayableModel m = mapper().toInvoicePayable(inv).model();
      assertEquals("CUS0226368", m.getInvoiceReference());
      assertNull(m.getSgEntity());
      assertNull(m.getProviderId(),
          "with no SIREN to look up, there is nothing to fall back to either");
    }
  }

  @Test
  @DisplayName("the lookup is mandatory")
  void lookupMandatory() {
    assertThrows(NullPointerException.class, () -> new EInvoiceFacadeMapper(null));
  }
}
