package com.sg.domaininterface.port.in;

import com.sg.domaininterface.model.einvoice.error.RegistrationOutcome;
import com.sg.domaininterface.model.invoice.ExtractedAttachment;
import com.sg.domaininterface.model.invoice.Invoice;
import java.util.List;

/**
 * Register an inbound e-invoice as a payable, or record why it could not be.
 *
 * <p>The interface exists so the REST layer can call this without seeing the implementation, and
 * therefore without seeing the mapper, the rules or the store behind it. A controller that
 * imported the implementation would drag the whole service graph onto its own classpath, and the
 * boundary would only be a naming convention.
 *
 * <p><b>This does not throw for a bad invoice.</b> A document with defects is registered with a
 * status and a list of errors — that is what the return value is for. Exceptions here mean the
 * infrastructure failed, not the sender.
 */
public interface InvoiceRegistrationService {

  /**
   * The flow value this pipeline writes to {@code t_invoice_payable.invoice_flow}.
   *
   * <p>On the interface rather than the implementation because callers assert on it, and the
   * column is what tells an e-invoicing row apart from a manual or SGAi one. That distinction is
   * part of the contract, not an implementation detail.
   */
  String FLOW_EINVOICE = "EINVOICE";

  /**
   * @param eInvoice            the document as received; must not be null
   * @param uploadedAttachments files uploaded alongside the request. When non-empty these are
   *                            the attachments and anything embedded in the document is ignored;
   *                            when empty or null, the embedded copies are used instead.
   * @return the outcome. The row was persisted either way — a failed registration is a data
   *         point, not a discard.
   */
  RegistrationOutcome register(Invoice eInvoice, List<ExtractedAttachment> uploadedAttachments);
}
