package com.sg.mapper.report;









/**
 * Thrown when an InvoicePayable cannot be mapped to a Flux 10 {@link
 * com.sg.domaininterface.model.report.ReportModel} — typically because mandatory data is
 * missing on the source side (sgEntity SIREN, invoice reference, currency, amount) or because
 * the referential lookup for one of the parties failed.
 *
 * <p>Distinct from
 * {@link com.sg.mapper.einvoice.EInvoiceMappingException} so error handlers in the
 * registration code path can react to the two mapping flows independently.
 */
public class ReportMappingException extends RuntimeException {
  /** Pinned so a rolling deployment cannot make an in-flight instance unreadable. */
  private static final long serialVersionUID = 1L;


  public ReportMappingException(String message) {
    super(message);
  }

  public ReportMappingException(String message, Throwable cause) {
    super(message, cause);
  }
}
