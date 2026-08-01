package com.example.invoice.service.registration.port;

import com.example.invoice.service.registration.Business;
import com.example.invoice.service.registration.EInvoiceMarker;
import com.example.invoice.service.registration.error.MappingError;
import com.example.invoice.service.registration.error.RegistrationOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Port for sending a human-readable alert about a failed registration.
 *
 * <p>Called exactly once per invoice that has any {@link MappingError}s, immediately after
 * persistence. The implementation (in {@code invoice-service-alerting}) formats a
 * comprehensive email body that lists every error — the requirement is "one alert per failed
 * invoice, covering all exceptions thrown" (spec point 10).
 *
 * <p>Implementations must return promptly and must not throw. The orchestrator wraps calls in
 * a try/catch and logs at WARN, matching the pattern already established by the party-
 * registration {@code AlertNotifier} in the domain module.
 */
@FunctionalInterface
public interface RegistrationAlertNotifier {

  void notify(RegistrationAlert alert);

  /**
   * @param invoicePayableId the DB id (once persisted) — useful in the email so ops can jump
   *                         straight to the row
   * @param invoiceReference human-facing ref
   * @param business         resolved business, or {@code null} if that itself failed
   * @param marker           parsed marker (may carry nulls)
   * @param outcome          final status + lifecycle decision + full error list
   * @param occurredAt       when the outcome was decided
   */
  record RegistrationAlert(
      Long invoicePayableId,
      String invoiceReference,
      Business business,
      EInvoiceMarker marker,
      RegistrationOutcome outcome,
      Instant occurredAt) {

    public RegistrationAlert {
      Objects.requireNonNull(outcome, "outcome");
      Objects.requireNonNull(marker, "marker");
      if (occurredAt == null) occurredAt = Instant.now();
    }

    /** All errors that fired. Empty means "shouldn't have been alerted". */
    public List<MappingError> errors() {
      return outcome.errors();
    }
  }

  /** No-op — for tests and for wiring an intentionally-silent deployment. */
  static RegistrationAlertNotifier none() {
    return alert -> { };
  }
}
