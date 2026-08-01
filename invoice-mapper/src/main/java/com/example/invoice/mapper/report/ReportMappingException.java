package com.example.invoice.mapper.report;

/**
 * Thrown when an InvoicePayable cannot be mapped to a Flux 10 {@link
 * com.example.invoice.mapper.report.model.ReportModel} — typically because mandatory data is
 * missing on the source side (sgEntity SIREN, invoice reference, currency, amount) or because
 * the referential lookup for one of the parties failed.
 *
 * <p>Distinct from
 * {@link com.example.invoice.mapper.einvoice.EInvoiceMappingException} so error handlers in the
 * registration code path can react to the two mapping flows independently.
 */
public class ReportMappingException extends RuntimeException {

  public ReportMappingException(String message) {
    super(message);
  }

  public ReportMappingException(String message, Throwable cause) {
    super(message, cause);
  }
}
