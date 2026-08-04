package com.sg.domaininterface.port.out;

/**
 * Port to check whether an incoming e-invoice has already been registered.
 *
 * <p>Consumed by {@link com.sg.domain.einvoice.rule.DuplicateInvoiceRule}
 * (spec rule 1). Implementation lives in the app module (JDBC over {@code t_invoice_payable});
 * this module doesn't know or care that persistence is SQL.
 *
 * <p>Match key: {@code provider_reference} = the value the mapper writes onto
 * {@link com.sg.domaininterface.model.payableinvoice.InvoicePayable#getProviderReference()}
 * (currently mirrors the e-invoice id). The lookup ONLY considers rows whose invoice_status is
 * {@code REGISTERED}; a prior CANCELLED / INCOMPLETE row does not block a fresh submission.
 */
@FunctionalInterface
public interface ExistingInvoicePayableLookup {

  /** @return true when a REGISTERED row already exists for this provider reference. */
  boolean existsRegistered(String providerReference);
}
