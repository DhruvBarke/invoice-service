package com.example.invoice.service.registration.rule;

import com.example.invoice.mapper.einvoice.MultipartExtractionService.ExtractedAttachment;
import com.example.invoice.mapper.einvoice.model.invoice.Invoice;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoiceItem;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoicePayableModel;
import com.example.invoice.service.registration.Business;
import com.example.invoice.service.registration.EInvoiceMarker;
import java.util.List;
import java.util.Objects;

/**
 * Everything a {@link ValidationRule} needs to reach a verdict.
 *
 * <p>Immutable snapshot assembled by the orchestrator after the mapping stack has run but
 * before persistence. Rules inspect fields as needed and return zero or more
 * {@link com.example.invoice.service.registration.error.MappingError}s.
 *
 * <p>Note that {@code model} and {@code items} may be {@code null} when the mapping stack
 * failed catastrophically upstream (e.g. malformed marker); rules must tolerate that. The
 * {@code originalEInvoice} is the source document — always non-null — so a rule that needs
 * something the mapper dropped can still reach for it.
 *
 * <p>Both {@code jsonAttachments} (base64 blobs extracted from the e-invoice body) and
 * {@code multipartAttachments} (raw file uploads on the multipart request) are surfaced
 * separately so rules can reason about presence in either channel independently — the
 * "missing attachment" rule (spec rule 2) needs to see both empty; the "trade file" rule
 * (spec rule 3) generally comes off multipart.
 */
public record ValidationContext(
    Business business,
    EInvoiceMarker marker,
    Invoice originalEInvoice,
    InvoicePayableModel model,
    List<InvoiceItem> items,
    List<ExtractedAttachment> jsonAttachments,
    List<ExtractedAttachment> multipartAttachments
) {

  public ValidationContext {
    Objects.requireNonNull(originalEInvoice, "originalEInvoice");
    // marker may be partially populated (business/feetype null) if parsing failed — allowed
    Objects.requireNonNull(marker, "marker");
    // business may be null if the marker didn't yield a known business — allowed
    items = items == null ? List.of() : List.copyOf(items);
    jsonAttachments = jsonAttachments == null ? List.of() : List.copyOf(jsonAttachments);
    multipartAttachments =
        multipartAttachments == null ? List.of() : List.copyOf(multipartAttachments);
  }

  /** True when any attachment (either channel) is present. */
  public boolean hasAnyAttachment() {
    return !jsonAttachments.isEmpty() || !multipartAttachments.isEmpty();
  }
}
