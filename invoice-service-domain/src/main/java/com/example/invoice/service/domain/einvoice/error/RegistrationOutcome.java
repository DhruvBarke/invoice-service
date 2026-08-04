package com.example.invoice.service.domain.einvoice.error;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Result of running the full validation + status-decision pipeline over an e-invoice.
 *
 * <p>Encodes three orthogonal facts:
 * <ul>
 *   <li>the {@link Status invoice status} the row should be persisted with;</li>
 *   <li>the {@link LifecycleEventType lifecycle event} to emit back to e-invoice-service (or
 *       {@code null} when the failure class doesn't warrant one — see {@link
 *       ErrorCode#EMPTY_LINE_ITEMS});</li>
 *   <li>the {@link #errors() list of every MappingError} accumulated during the run — the
 *       alert email lists all of them, and the persistence layer stores them as JSON so
 *       downstream tooling can index by error code.</li>
 * </ul>
 *
 * <p><b>Precedence rule.</b> {@link #decide(List)} enforces:
 * <ol>
 *   <li>Any {@code MappingError} whose {@link ErrorCode#lifecycleEvent()} is {@code REFUSED}
 *       wins — the row's status is {@code CANCELLED}, the event is {@code REFUSED}, and the
 *       reason code comes from the first refused-class error encountered.</li>
 *   <li>Else if any error is {@code SUSPENDED} — status {@code CANCELLED}, event
 *       {@code SUSPENDED}, first suspended-class reason code.</li>
 *   <li>Else if any error is {@code EMPTY_LINE_ITEMS} — status {@code INCOMPLETE}, no
 *       lifecycle event. The invoice can be completed in-app.</li>
 *   <li>Else — no errors at all — status {@code REGISTERED}.</li>
 * </ol>
 *
 * <p>The {@code CANCELLED} + comment convention for duplicates (spec rule 1) is baked into
 * this precedence: {@code DUPLICATE_INVOICE} is a refused-class code, so it lands on the
 * REFUSED branch and its detail message becomes the row's {@code comment}.
 */
public record RegistrationOutcome(
    Status status,
    LifecycleEventType lifecycleEvent,
    String lifecycleReasonCode,
    List<MappingError> errors,
    String comment
) {

  public RegistrationOutcome {
    Objects.requireNonNull(status, "status");
    errors = errors == null ? List.of() : List.copyOf(errors);
  }

  /**
   * Status values this pipeline may write. Kept as a first-class enum in this module rather
   * than reusing the einvoice-service {@code InvoiceStatus} enum, so that adding a new
   * registration outcome (e.g. {@code AWAITING_MANUAL_REVIEW}) doesn't require a change in a
   * sibling repository.
   */
  public enum Status {
    /** Everything mapped and validated cleanly. */
    REGISTERED,
    /** Mapping or validation failed. Row stored, lifecycle event queued if applicable. */
    CANCELLED,
    /**
     * Row stored with missing line items. No lifecycle event; users complete the invoice in
     * the app. Fires only when {@link ErrorCode#EMPTY_LINE_ITEMS} is the sole failure.
     */
    INCOMPLETE
  }

  /** Convenience: was the invoice registered cleanly? */
  public boolean isRegistered() {
    return status == Status.REGISTERED;
  }

  /** Convenience: did any failure fire? */
  public boolean hasErrors() {
    return !errors.isEmpty();
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Decision function
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Apply the precedence rule: REFUSED &gt; SUSPENDED &gt; INCOMPLETE &gt; REGISTERED.
   * Comment is the detail message of the winning error (or {@code null} when no errors).
   */
  /**
   * The same outcome, plus an error discovered after the decision was made.
   *
   * <p>The status, lifecycle event and comment are all left alone. Something that fails after
   * the verdict — the lifecycle publisher being down, say — does not change what the invoice
   * itself was found to be, and re-deciding would let an infrastructure fault rewrite a
   * REGISTERED invoice into a refused one.
   *
   * <p>It does have to reach the alert, though, which is the whole reason this exists: the
   * errors list is copied at construction, so adding to the list the outcome was built from
   * changes nothing, and the extra error would be recorded nowhere and told to no one.
   */
  public RegistrationOutcome withAdditionalError(MappingError extra) {
    if (extra == null) {
      return this;
    }
    List<MappingError> combined = new ArrayList<>(errors);
    combined.add(extra);
    return new RegistrationOutcome(status, lifecycleEvent, lifecycleReasonCode, combined, comment);
  }

  public static RegistrationOutcome decide(List<MappingError> errors) {
    if (errors == null || errors.isEmpty()) {
      return new RegistrationOutcome(Status.REGISTERED, null, null, List.of(), null);
    }

    MappingError refused = firstWithLifecycle(errors, LifecycleEventType.REFUSED);
    if (refused != null) {
      return new RegistrationOutcome(Status.CANCELLED,
          LifecycleEventType.REFUSED, refused.code().reasonCode(),
          errors, refused.detail());
    }

    MappingError suspended = firstWithLifecycle(errors, LifecycleEventType.SUSPENDED);
    if (suspended != null) {
      return new RegistrationOutcome(Status.CANCELLED,
          LifecycleEventType.SUSPENDED, suspended.code().reasonCode(),
          errors, suspended.detail());
    }

    // Only alert-only errors left (EMPTY_LINE_ITEMS today).
    MappingError alertOnly = errors.get(0);
    return new RegistrationOutcome(Status.INCOMPLETE, null, null, errors, alertOnly.detail());
  }

  private static MappingError firstWithLifecycle(List<MappingError> errors, LifecycleEventType t) {
    for (MappingError e : errors) {
      if (e.code().lifecycleEvent() == t) return e;
    }
    return null;
  }
}
