package com.sg.domaininterface.rule.einvoice;

import com.sg.domaininterface.model.invoice.ExtractedAttachment;
import com.sg.domaininterface.model.invoice.Invoice;
import com.sg.domaininterface.model.payableinvoice.InvoiceItem;
import com.sg.domaininterface.model.payableinvoice.InvoicePayableModel;
import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.model.einvoice.EInvoiceMarker;
import java.util.List;
import java.util.Objects;

/**
 * Everything a {@link ValidationRule} needs to reach a verdict.
 *
 * <p>Immutable snapshot assembled by the orchestrator after the mapping stack has run but
 * before persistence. Rules inspect fields as needed and return zero or more
 * {@link com.sg.domaininterface.model.einvoice.error.MappingError}s.
 *
 * <p>Note that {@code model} and {@code items} may be {@code null} when the mapping stack
 * failed catastrophically upstream (e.g. malformed marker); rules must tolerate that. The
 * {@code originalEInvoice} is the source document — always non-null — so a rule that needs
 * something the mapper dropped can still reach for it.
 *
 * <p>{@code attachments} is whichever channel actually supplied files, and {@code channel} says
 * which one that was. Only one can win: an upload alongside the request means the sender chose
 * to send files that way, and the copies embedded in the document body are the fallback for
 * senders that cannot. A rule asking "was anything attached?" wants one list; a rule reporting
 * a corrupt file wants to name the channel it came from, which is why the discriminator travels
 * with it rather than being inferred later.
 */
public record ValidationContext(
    Business business,
    EInvoiceMarker marker,
    Invoice originalEInvoice,
    InvoicePayableModel model,
    List<InvoiceItem> items,
    List<ExtractedAttachment> attachments,
    AttachmentChannel channel
) {

  public ValidationContext {
    Objects.requireNonNull(originalEInvoice, "originalEInvoice");
    // marker may be partially populated (business/feetype null) if parsing failed — allowed
    Objects.requireNonNull(marker, "marker");
    // business may be null if the marker didn't yield a known business — allowed
    items = items == null ? List.of() : List.copyOf(items);
    attachments = attachments == null ? List.of() : List.copyOf(attachments);
    channel = channel == null ? AttachmentChannel.EINVOICE_BODY : channel;
  }

  /** True when the winning channel supplied at least one file. */
  public boolean hasAnyAttachment() {
    return !attachments.isEmpty();
  }
}
