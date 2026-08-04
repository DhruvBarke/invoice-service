package com.sg.mapper.report;

import com.sg.domaininterface.model.payableinvoice.InvoiceItem;
import com.sg.domaininterface.model.payableinvoice.InvoicePayableModel;
import com.sg.domaininterface.model.report.Invoice;
import com.sg.domaininterface.model.report.Report;
import com.sg.domaininterface.model.report.ReportDocument;
import com.sg.domaininterface.model.report.ReportModel;
import com.sg.domaininterface.model.report.ReportPeriod;
import com.sg.domaininterface.model.report.TransactionsReport;
import com.sg.domaininterface.port.out.PartyRegistrationLookup;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Top-level composer for the {@code InvoicePayable → ReportModel} flow. Assembles a full
 * {@link ReportModel} from an {@link InvoicePayableModel} + optional line items by delegating
 * to the per-concern sub-mappers.
 *
 * <p>Ported from A's {@code ReportFacadeMapper} — was a MapStruct-generated Spring
 * {@code abstract class}; now a {@code final} plain class with two constructor-injected
 * collaborators ({@link PartyRegistrationLookup}, {@link ReportFlowConfig}).
 *
 * <p><b>Not to be confused with</b> the existing {@link
 * com.sg.mapper.ReportFacadeMapper} in the parent package — that one is a party-
 * lookup helper for report queries ("give me every duplicate for this SIRET"). This one is
 * the Flux 10 report <em>building</em> facade. They serve different needs and live in
 * different packages; keep them straight when injecting.
 *
 * <p>Direction is one-way — Flux 10 reports flow SG → PA only; the reverse (PA → SG) is a
 * status feedback path handled elsewhere.
 */
public final class ReportFacadeMapper {

  private final PartyRegistrationLookup lookup;
  private final ReportFlowConfig config;

  public ReportFacadeMapper(PartyRegistrationLookup lookup, ReportFlowConfig config) {
    this.lookup = Objects.requireNonNull(lookup, "lookup");
    this.config = Objects.requireNonNull(config, "config");
  }

  /**
   * Build a {@link ReportModel} from a payable + its items.
   *
   * @param model source InvoicePayableModel (mandatory, non-null).
   * @param items line items; null / empty is fine — {@link Invoice#getLine()} stays null.
   */
  public ReportModel toReportModel(InvoicePayableModel model, List<InvoiceItem> items) {
    if (model == null) throw new ReportMappingException("InvoicePayableModel is null");

    LocalDateTime now = LocalDateTime.now();

    ReportDocument doc = ReportDocumentMapper.toReportDocument(model, config, lookup, now);
    Invoice invoice = ReportInvoiceMapper.toInvoice(model, items, config);

    ReportPeriod period = ReportPeriod.builder()
        .startDate(model.getTradingStartDate())
        .endDate(model.getTradingEndDate())
        .build();

    TransactionsReport tr = TransactionsReport.builder()
        .reportPeriod(period)
        .invoice(List.of(invoice))
        .build();

    Report report = Report.builder()
        .reportDocument(doc)
        .transactionsReport(tr)
        .build();

    LocalDate today = now.toLocalDate();
    return ReportModel.builder()
        .sgEntity(model.getSgEntity())
        .periodStartDate(model.getTradingStartDate())
        .periodEndDate(model.getTradingEndDate())
        .status("DRAFT")
        .transmissionType(doc.getTypeCode())
        .createdDate(today)
        .lastUpdatedDate(today)
        .createdByUser(model.getCreatedByUser())
        .lastUpdatedByUser(model.getLastUpdatedByUser())
        .isDeleted(false)
        .report(report)
        .build();
  }
}
