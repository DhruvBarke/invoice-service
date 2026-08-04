package com.sg.mapper.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domaininterface.model.payableinvoice.InvoiceItem;
import com.sg.domaininterface.model.payableinvoice.InvoicePayable;
import com.sg.domaininterface.model.payableinvoice.InvoicePayableModel;
import com.sg.domaininterface.model.report.ReportModel;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.port.out.PartyRegistrationLookup;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * End-to-end smoke test for the Flux 10 report mapping stack. As with {@link
 * com.sg.mapper.einvoice.EInvoiceMappingSmokeTest} the whole point is that the
 * facade is constructible from a four-line stub — no Spring, no cache, no database.
 */
class ReportMappingSmokeTest {

  private static final PartyRegistrationDetails SG =
      new PartyRegistrationDetails(
          null, null, null, null, null, null,
          "BDR-SG-01", "Societe Generale", "SG",
          "552120222", "55212022200013", List.of());

  private static PartyRegistrationLookup stub() {
    return new PartyRegistrationLookup() {
      @Override public Optional<PartyRegistrationDetails> findByBdrId(String b) { return Optional.empty(); }
      @Override public Optional<PartyRegistrationDetails> findBySiren(String s) {
        return SG.siren().equals(s) ? Optional.of(SG) : Optional.empty();
      }
      @Override public Optional<PartyRegistrationDetails> findBySiret(String s) { return Optional.empty(); }
      @Override public List<PartyRegistrationDetails> findAllBySiret(String s) { return List.of(); }
    };
  }

  @Test
  void buildsReportModelFromPayable() {
    var facade = new ReportFacadeMapper(
        stub(),
        ReportFlowConfig.builder().platformMatricule("PA01").platformName("Test PA").build());
    var svc = new ReportMappingService(facade);

    var payable = InvoicePayable.builder()
        .vatAmount(new BigDecimal("20.00"))
        .vatRate(new BigDecimal("20.00"))
        .paymentDueDate(LocalDate.of(2026, 5, 14))
        .build();

    var model = InvoicePayableModel.builder()
        .invoiceReference("REP-2026-0001")
        .invoiceDate(LocalDate.of(2026, 4, 14))
        .invoiceType("DEBIT")
        .currency("EUR")
        .amount(new BigDecimal("120.00"))
        .providerId("999999999")
        .sgEntity("552120222")
        .tradingStartDate(LocalDate.of(2026, 4, 1))
        .tradingEndDate(LocalDate.of(2026, 4, 30))
        .createdByUser("alice")
        .invoicePayable(payable)
        .build();

    var item = InvoiceItem.builder()
        .feeType("REGL").feeAmount(new BigDecimal("100.00"))
        .notionQuantity(BigDecimal.ONE).build();

    ReportModel report = svc.toReport(model, List.of(item));

    assertNotNull(report);
    assertEquals("552120222", report.getSgEntity());
    assertEquals("DRAFT", report.getStatus());
    assertEquals("IN", report.getTransmissionType());
    assertEquals("alice", report.getCreatedByUser());

    var doc = report.getReport().getReportDocument();
    assertNotNull(doc.getSender());
    assertEquals("PA01", doc.getSender().getId().getValue());
    // Issuer name populated from PartyRegistrationLookup
    assertEquals("Societe Generale", doc.getIssuer().getName());
    // Transmission id follows the sgSiren_ref_yyyyMMddHHmmss shape
    assertTrue(doc.getId().startsWith("552120222_REP-2026-0001_"), doc.getId());

    var invoice = report.getReport().getTransactionsReport().getInvoice().get(0);
    assertEquals("REP-2026-0001", invoice.getId());
    assertEquals("380", invoice.getTypeCode()); // UNTDID 1001 DEBIT
    assertEquals("EUR", invoice.getCurrencyCode());
    assertNotNull(invoice.getSeller());
    assertNotNull(invoice.getBuyer());
    assertEquals(1, invoice.getLine().size());
  }

  @Test
  void emptyItemsListLeavesInvoiceLineNull() {
    var facade = new ReportFacadeMapper(stub(), ReportFlowConfig.builder().build());
    var svc = new ReportMappingService(facade);
    var model = InvoicePayableModel.builder()
        .invoiceReference("R").invoiceDate(LocalDate.now())
        .invoiceType("DEBIT").currency("EUR").amount(BigDecimal.ONE)
        .sgEntity("552120222")
        .invoicePayable(new InvoicePayable())
        .build();

    ReportModel report = svc.toReport(model, List.of());
    assertNull(report.getReport().getTransactionsReport().getInvoice().get(0).getLine());
  }
}
