package com.sg.domaininterface.port.out;

import com.sg.domaininterface.model.einvoice.error.MappingError;
import com.sg.domaininterface.model.payableinvoice.InvoicePayableModel;
import java.util.List;

/**
 * Fill the payable fields that cannot be derived from the document.
 *
 * <p>A port rather than a concrete collaborator so the registration use case depends on the same
 * kind of thing for enrichment as it does for everything else, and so the guard it wraps this call
 * in is a guard against something rather than a formality.
 *
 * <p><b>Implementations report; they do not throw.</b> Nothing filled in here decides whether an
 * invoice is valid, so a referential being unavailable must produce an alert-only error and an
 * unset field — never a refusal the sender is asked to act on.
 *
 * <p><b>The model is mutated in place.</b> It is the same object the mapper produced and the store
 * will write; handing back a copy would leave the caller with two versions of one invoice and no
 * rule about which the row is built from.
 */
@FunctionalInterface
public interface InvoiceEnrichmentPort {

  /**
   * @param model the mapped payable, or {@code null} when mapping failed upstream
   * @return one error per thing that could not be worked out; empty when everything resolved, or
   *         when there was nothing to resolve
   */
  List<MappingError> enrich(InvoicePayableModel model);
}
