package com.example.invoice.mapper.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.invoice.mapper.einvoice.DocumentReferenceMapper.AttachmentPayload;
import com.example.invoice.mapper.einvoice.EInvoiceFacadeMapper.MappedResult;
import com.example.invoice.mapper.einvoice.model.invoice.Invoice;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoiceItem;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoicePayable;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoicePayableModel;
import com.example.invoice.service.domain.model.PartyRegistrationDetails;
import com.example.invoice.service.domain.port.in.PartyRegistrationLookup;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * End-to-end smoke test for the einvoice mapping stack. Constructs the facade with a stub
 * {@link PartyRegistrationLookup} (no Spring, no cache, no database — the whole point of the
 * enforcer rule on this module) and round-trips a hand-built model through both directions.
 */
class EInvoiceMappingSmokeTest {

  private static final PartyRegistrationDetails PROVIDER =
      new PartyRegistrationDetails(
          null, null, null, null, null, null,
          "BDR-PROVIDER-01", "Acme Trading SA", "ACME",
          "123456789", "12345678900010", List.of());

  private static final PartyRegistrationDetails SG_ENTITY =
      new PartyRegistrationDetails(
          null, null, null, null, null, null,
          "BDR-SG-01", "Societe Generale", "SG",
          "552120222", "55212022200013", List.of());

  private static PartyRegistrationLookup stub() {
    return new PartyRegistrationLookup() {
      @Override public Optional<PartyRegistrationDetails> findByBdrId(String b) { return Optional.empty(); }
      @Override public Optional<PartyRegistrationDetails> findBySiren(String s) {
        if (PROVIDER.siren().equals(s)) return Optional.of(PROVIDER);
        if (SG_ENTITY.siren().equals(s)) return Optional.of(SG_ENTITY);
        return Optional.empty();
      }
      @Override public Optional<PartyRegistrationDetails> findBySiret(String s) { return Optional.empty(); }
      @Override public List<PartyRegistrationDetails> findAllBySiret(String s) { return List.of(); }
    };
  }

  @Test
  void outboundMapsPayableToInvoiceEnd2End() {
    var svc = new EInvoiceMappingService(new EInvoiceFacadeMapper(stub()), new MultipartExtractionService());

    var payable = InvoicePayable.builder()
        .providerName("Acme Trading SA")
        .sgEntityName("Societe Generale")
        .vatAmount(new BigDecimal("20.00"))
        .vatRate(new BigDecimal("20.00"))
        .build();
    var model = InvoicePayableModel.builder()
        .invoiceReference("INV-2026-0001")
        .invoiceDate(LocalDate.of(2026, 4, 14))
        .invoiceType("DEBIT")
        .currency("EUR")
        .amount(new BigDecimal("120.00"))
        .providerId("123456789")
        .sgEntity("552120222")
        .invoicePayable(payable)
        .build();
    var item = InvoiceItem.builder()
        .feeAmount(new BigDecimal("100.00"))
        .feeCurrency("EUR")
        .feeType("Custody").notionQuantity(BigDecimal.ONE).build();

    Invoice inv = svc.toEInvoice(model, List.of(item), null, null);

    assertNotNull(inv);
    assertEquals("INV-2026-0001", inv.getId());
    assertEquals("380", inv.getInvoiceTypeCode().getValue());
    assertEquals("EUR", inv.getDocumentCurrencyCode().getValue());
    assertEquals(new BigDecimal("120.00"), inv.getLegalMonetaryTotal().getPayableAmount().getValue());
    assertEquals(1, inv.getInvoiceLine().size());
  }

  @Test
  void inboundMapsInvoiceToPayableAndFillsPartyFromReferential() {
    var svc = new EInvoiceMappingService(new EInvoiceFacadeMapper(stub()), new MultipartExtractionService());

    // Build the outbound side first, then round-trip.
    var out = svc.toEInvoice(
        InvoicePayableModel.builder()
            .invoiceReference("INV-2026-0002")
            .invoiceDate(LocalDate.of(2026, 5, 1))
            .invoiceType("DEBIT")
            .currency("EUR")
            .amount(new BigDecimal("240.00"))
            .providerId("123456789")
            .sgEntity("552120222")
            .invoicePayable(InvoicePayable.builder()
                .vatAmount(new BigDecimal("40.00"))
                .vatRate(new BigDecimal("20.00"))
                .providerName("Acme Trading SA")
                .sgEntityName("Societe Generale")
                .build())
            .build(),
        List.of(), null, null);

    MappedResult back = svc.toInvoicePayable(out);

    assertNotNull(back.model());
    // sgEntity is what came off the outbound UBL — the SIREN
    assertEquals("552120222", back.model().getSgEntity());
    // providerId is now the internalCode (goldenBdrId) resolved from the referential
    assertEquals("BDR-PROVIDER-01", back.model().getProviderId());
    // referential populated the payable enrichments
    assertEquals("Societe Generale", back.model().getInvoicePayable().getSgEntityName());
    assertEquals("SG", back.model().getInvoicePayable().getSgEntityMnemonic());
    assertEquals("Acme Trading SA", back.model().getInvoicePayable().getProviderName());
    // attachments deliberately left null — registration endpoint fills them
    assertNull(back.model().getInvoicePayable().getInvoicePdfId());
  }

  @Test
  void attachmentPayloadEndsUpInAdditionalDocumentReference() {
    var svc = new EInvoiceMappingService(new EInvoiceFacadeMapper(stub()), new MultipartExtractionService());

    byte[] fakePdf = new byte[] {0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34}; // %PDF-1.4
    var pdf = new AttachmentPayload("pdf-id-1", fakePdf, "invoice.pdf", "application/pdf");

    Invoice inv = svc.toEInvoice(
        InvoicePayableModel.builder()
            .invoiceReference("R").invoiceDate(LocalDate.now())
            .invoiceType("DEBIT").currency("EUR").amount(BigDecimal.ONE)
            .providerId("123456789").sgEntity("552120222").build(),
        List.of(), pdf, null);

    assertNotNull(inv.getAdditionalDocumentReference());
    assertEquals(1, inv.getAdditionalDocumentReference().size());
    assertEquals("pdf-id-1", inv.getAdditionalDocumentReference().get(0).getId().getValue());
  }
}
