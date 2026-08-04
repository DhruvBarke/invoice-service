package com.sg.domaininterface.port.out;

import com.sg.domaininterface.model.einvoice.error.LifecycleEventType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Port for recording a lifecycle event that needs to be delivered back to the e-invoice-service.
 *
 * <p><b>Data-layer only for this pass.</b> The concrete implementation writes to the
 * {@code t_invoice_payable} row's lifecycle columns
 * ({@code lifecycle_event_type}, {@code lifecycle_reason_code}, {@code lifecycle_event_status =
 * PENDING}, {@code lifecycle_payload}). A future scheduler (out of scope here) picks up
 * PENDING rows and posts them to the e-invoice-service HTTP endpoint. This port is the seam
 * that future scheduler wires into.
 *
 * <p>Calls are idempotent by {@code invoicePayableId} — the orchestrator invokes exactly once
 * per registration, immediately after persistence, so the row's lifecycle columns are set in
 * the same transaction context. Implementations that batch or retry should use the id as the
 * dedup key.
 *
 * <p>No refused/suspended events are emitted for INCOMPLETE outcomes — the orchestrator only
 * calls this port when {@link com.sg.domaininterface.model.einvoice.error.RegistrationOutcome#lifecycleEvent()}
 * is non-null.
 */
public interface LifecycleEventPublisher {

  void publish(PendingLifecycleEvent event);

  /**
   * @param invoicePayableId the {@code t_invoice_payable} primary key
   * @param invoiceReference the human-facing invoice ref (echoed into the outbound event)
   * @param type             REFUSED or SUSPENDED
   * @param reasonCode       matching einvoice-service {@code t_reason_code_status} seed
   *                         (e.g. DOUBLON, NON_CONFORME, JUSTIF_ABS, SIRET_ERR)
   * @param comment          free-text detail (usually the winning {@code MappingError#detail})
   * @param occurredAt       when the failure was decided
   */
  record PendingLifecycleEvent(
      UUID invoicePayableId,
      String invoiceReference,
      LifecycleEventType type,
      String reasonCode,
      String comment,
      Instant occurredAt) {

    public PendingLifecycleEvent {
      Objects.requireNonNull(invoicePayableId, "invoicePayableId");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(reasonCode, "reasonCode");
      if (occurredAt == null) occurredAt = Instant.now();
    }
  }
}
