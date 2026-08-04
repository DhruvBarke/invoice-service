package com.example.invoice.service.domain.einvoice.port;

import com.example.invoice.service.domain.einvoice.EInvoiceMarker;
import com.example.invoice.service.domain.einvoice.error.MappingError;
import com.example.invoice.service.domain.model.invoice.ExtractedAttachment;
import com.example.invoice.service.domain.model.invoice.Invoice;
import com.example.invoice.service.domain.model.payableinvoice.InvoiceItem;
import com.example.invoice.service.domain.model.payableinvoice.InvoicePayableModel;
import java.util.List;
import java.util.Objects;

/**
 * The one way into the mapping stack.
 *
 * <p>Everything that reads the inbound e-invoice happens behind this port, in a single call:
 * parsing the receiver marker, resolving the fee identity, building the payable, and pulling out
 * the embedded attachments. That grouping is the point. Those steps all interrogate the same
 * document, they all fail in the same way — a field the sender got wrong — and when they were
 * split across two modules the results had to be stitched back together by an orchestrator that
 * reached into a finished model to patch two fields onto it. Mapping happened in two places and
 * neither one could tell you, on its own, what the invoice had said.
 *
 * <p><b>Errors come back, they are not thrown.</b> A {@link MappingResult} carries however many
 * {@link MappingError}s the document earned, and mapping continues after each one. An invoice
 * with a malformed marker AND an unresolvable fee type reports both; if the first aborted, the
 * sender would fix it only to discover the second on resubmission. The caller decides what the
 * accumulated errors mean — see
 * {@link com.example.invoice.service.domain.einvoice.error.RegistrationOutcome#decide}.
 *
 * <p><b>Implementations do no I/O beyond the referential lookups they are constructed with.</b>
 * No alert is sent from here, no row is written. The result is a value; the caller dispatches
 * one alert per invoice from it. Sending mid-mapping would mean an invoice with four defects
 * generating four emails, and a mapping that later failed outright having already told someone
 * it was fine.
 */
public interface EInvoiceMappingPort {

  /**
   * Map an inbound e-invoice, collecting every defect found along the way.
   *
   * @param eInvoice the document as received; never {@code null}
   * @return a result that is always non-null. {@code model} is {@code null} only when mapping
   *         could not produce one at all, in which case {@code errors} explains why.
   */
  MappingResult map(Invoice eInvoice);

  /**
   * @param model                the mapped envelope, or {@code null} if mapping failed outright.
   *                             Its {@code invoiceReference} is deliberately unset — that value
   *                             is minted by the store, not by the sender.
   * @param items                the mapped lines, possibly empty
   * @param embeddedAttachments  attachments carried inside the e-invoice body. Used only when
   *                             the caller supplies none of its own; see
   *                             {@code InvoiceRegistrationService#register}.
   * @param marker               the parsed receiver marker. Never {@code null} — an unparseable
   *                             marker yields {@link EInvoiceMarker#empty()} plus an error, so
   *                             callers never null-check before reading {@code business()}.
   * @param feeId                the referential's fee id, when the matcher resolved the marker's
   *                             fee-type token. Null when it did not.
   * @param feeType              the referential's canonical fee type when resolved; otherwise
   *                             the raw token off the marker, so the row records what the sender
   *                             actually said even when nothing matched it.
   * @param errors               every defect found, in detection order. Empty means clean.
   */
  record MappingResult(
      InvoicePayableModel model,
      List<InvoiceItem> items,
      List<ExtractedAttachment> embeddedAttachments,
      EInvoiceMarker marker,
      String feeId,
      String feeType,
      List<MappingError> errors) {

    public MappingResult {
      Objects.requireNonNull(marker, "marker");
      items = items == null ? List.of() : List.copyOf(items);
      embeddedAttachments =
          embeddedAttachments == null ? List.of() : List.copyOf(embeddedAttachments);
      errors = errors == null ? List.of() : List.copyOf(errors);
    }
  }
}
