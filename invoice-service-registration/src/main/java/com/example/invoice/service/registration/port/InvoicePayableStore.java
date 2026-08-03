package com.example.invoice.service.registration.port;

import com.example.invoice.mapper.einvoice.MultipartExtractionService.ExtractedAttachment;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoiceItem;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoicePayableModel;
import com.example.invoice.service.registration.Business;
import com.example.invoice.service.registration.error.RegistrationOutcome;
import java.util.List;
import java.util.Objects;

/**
 * Port for persisting one registration.
 *
 * <p>Called once per registration by the orchestrator, whatever the outcome — a failed
 * registration is a data point, not a discard. The concrete implementation
 * ({@code JdbcInvoicePayableStore}, in the app module) spreads the request across three
 * tables that correlate on {@code invoice_reference}:
 *
 * <ul>
 *   <li>{@code t_invoice_payable} — the envelope, with the {@code InvoicePayable} as JSON</li>
 *   <li>{@code t_invoice_item} — one row per line</li>
 *   <li>{@code t_invoice_documents} — one row per attachment, from either channel</li>
 * </ul>
 *
 * <p><b>The implementation mints {@code invoiceReference}.</b> The mapper leaves it null,
 * because the incoming e-invoice's id is unique only within the issuing supplier — that value
 * is the {@code providerReference} instead. The store assigns the real reference from a
 * database sequence and writes it back onto {@link PersistRequest#model()}, so anything that
 * runs after persistence quotes the same value the row carries.
 */
public interface InvoicePayableStore {

  /** @return the {@code t_invoice_payable} primary key. */
  long persist(PersistRequest request);

  /**
   * @param business             resolved business, or {@code null} when parsing failed
   * @param feeId                resolved by {@link com.example.invoice.mapper.einvoice.FeeTypeMatcher},
   *                             or {@code null}
   * @param feeType              the referential's canonical fee type when the matcher resolved
   *                             it, else the raw marker tail, else {@code null}
   * @param source               hard-coded {@code "EINVOICE"} for this pipeline; left open so
   *                             future sources can share the tables
   * @param model                the mapped envelope — {@code null} when mapping failed outright.
   *                             Its {@code invoiceReference} is populated by the store.
   * @param items                invoice lines, possibly empty
   * @param jsonAttachments      attachments extracted from the e-invoice body
   * @param multipartAttachments attachments uploaded alongside the request. Kept separate from
   *                             the body ones all the way to the row: "no attachment" reads very
   *                             differently depending on which channel was empty.
   * @param outcome              the decided status, lifecycle event and full error list
   */
  record PersistRequest(
      Business business,
      String feeId,
      String feeType,
      String source,
      InvoicePayableModel model,
      List<InvoiceItem> items,
      List<ExtractedAttachment> jsonAttachments,
      List<ExtractedAttachment> multipartAttachments,
      RegistrationOutcome outcome) {

    public PersistRequest {
      Objects.requireNonNull(outcome, "outcome");
      if (source == null || source.isBlank()) {
        source = "EINVOICE";
      }
      items = items == null ? List.of() : List.copyOf(items);
      jsonAttachments = jsonAttachments == null ? List.of() : List.copyOf(jsonAttachments);
      multipartAttachments =
          multipartAttachments == null ? List.of() : List.copyOf(multipartAttachments);
    }
  }
}
