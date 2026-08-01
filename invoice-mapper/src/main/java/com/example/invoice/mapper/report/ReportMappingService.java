package com.example.invoice.mapper.report;

import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoiceItem;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoicePayableModel;
import com.example.invoice.mapper.report.model.ReportModel;
import java.util.List;
import java.util.Objects;

/**
 * Public facade for the {@code InvoicePayable → ReportModel} flow. Callers depend on this
 * rather than on {@link ReportFacadeMapper} so the internal mapper graph can be refactored
 * without breaking them.
 *
 * <p>Ported from A's {@code ReportMappingService} — the plain-facade shape was already there,
 * only the underlying facade type changed (MapStruct bean → plain class).
 *
 * <p>Direction is one-way: SG builds reports and submits to the PA. The PA-side status
 * feedback is a distinct code path handled outside this module.
 *
 * <p>Typical wiring:
 * <pre>{@code
 *   PartyRegistrationLookup lookup = ...;
 *   ReportFlowConfig config = ReportFlowConfig.builder()
 *       .platformMatricule("PA01")
 *       .platformName("MY PA")
 *       .build();
 *   var facade  = new ReportFacadeMapper(lookup, config);
 *   var service = new ReportMappingService(facade);
 *   ReportModel report = service.toReport(model, items);
 * }</pre>
 */
public class ReportMappingService {

  private final ReportFacadeMapper facade;

  public ReportMappingService(ReportFacadeMapper facade) {
    this.facade = Objects.requireNonNull(facade, "facade");
  }

  /**
   * Map an {@link InvoicePayableModel} + its line items into a Flux 10.1 {@link ReportModel}.
   * Line items may be null or empty — the resulting {@code Invoice.line} stays absent per the
   * user's directive.
   */
  public ReportModel toReport(InvoicePayableModel model, List<InvoiceItem> items) {
    return facade.toReportModel(model, items);
  }
}
