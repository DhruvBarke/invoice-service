package com.sg.domaininterface.model.einvoice.error;

/**
 * Lifecycle-event classes we can emit back to e-invoice-service for a malformed invoice.
 *
 * <p>Values are aligned with the einvoice-service {@code InvoiceStatus} enum's CDAR codes
 * (see {@code com.sg.einvoicing.domain.model.common.InvoiceStatus}). We only emit the two
 * end-of-pipeline classes:
 *
 * <ul>
 *   <li><b>REFUSED (210)</b> — the invoice cannot be processed at all: mapping failed,
 *       duplicate, fee type unresolvable. Terminal from the peer's point of view.</li>
 *   <li><b>SUSPENDED (208)</b> — a required attachment or trade file is missing. The invoice
 *       is parked pending correction; the sender can supply the missing content.</li>
 * </ul>
 *
 * <p>Errors that fire no lifecycle event (e.g.
 * {@link ErrorCode#EMPTY_LINE_ITEMS} → {@code INCOMPLETE} status) return {@code null} from
 * {@link ErrorCode#lifecycleEvent()}.
 *
 * <p><b>Precedence rule</b> ({@link RegistrationOutcome} enforces it): when a single
 * registration yields both REFUSED and SUSPENDED errors, REFUSED wins.
 */
public enum LifecycleEventType {

  /** CDAR 210 — invoice cannot be processed. */
  REFUSED(210),

  /** CDAR 208 — invoice parked pending correction. */
  SUSPENDED(208);

  private final int cdarCode;

  LifecycleEventType(int cdarCode) {
    this.cdarCode = cdarCode;
  }

  public int cdarCode() {
    return cdarCode;
  }
}
