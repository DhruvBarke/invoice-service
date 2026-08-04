package com.sg.domain.einvoice;

import com.sg.domain.einvoice.rule.ValidationRegistry;
import com.sg.domaininterface.model.einvoice.EInvoiceMarker;
import com.sg.domaininterface.model.einvoice.error.ErrorCode;
import com.sg.domaininterface.model.einvoice.error.MappingError;
import com.sg.domaininterface.model.einvoice.error.RegistrationOutcome;
import com.sg.domaininterface.model.invoice.ExtractedAttachment;
import com.sg.domaininterface.model.invoice.Invoice;
import com.sg.domaininterface.model.payableinvoice.InvoiceDocumentPayable;
import com.sg.domaininterface.port.out.EInvoiceMappingPort.MappingResult;
import com.sg.domaininterface.port.out.EInvoiceMappingPort;
import com.sg.domaininterface.port.out.InvoicePayableStore.PersistRequest;
import com.sg.domaininterface.port.out.InvoicePayableStore;
import com.sg.domaininterface.port.out.LifecycleEventPublisher.PendingLifecycleEvent;
import com.sg.domaininterface.port.out.LifecycleEventPublisher;
import com.sg.domaininterface.port.out.RegistrationAlertNotifier.RegistrationAlert;
import com.sg.domaininterface.port.out.RegistrationAlertNotifier;
import com.sg.domaininterface.rule.einvoice.AttachmentChannel;
import com.sg.domaininterface.rule.einvoice.ValidationContext;
import com.sg.domaininterface.rule.einvoice.ValidationRule;
import com.sg.domaininterface.port.in.InvoiceRegistrationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The registration use case: turn an inbound e-invoice into a persisted payable, or into a
 * recorded reason why not.
 *
 * <p>Five collaborators, every one of them a port, none of them Spring-aware:
 *
 * <ul>
 *   <li>{@link EInvoiceMappingPort} — reads the document. Marker, fee identity, payable, lines
 *       and embedded attachments all come back from one call, along with whatever went wrong.</li>
 *   <li>{@link ValidationRegistry} — the rules configured for the resolved business.</li>
 *   <li>{@link InvoicePayableStore} — writes the rows.</li>
 *   <li>{@link LifecycleEventPublisher} — records the REFUSED / SUSPENDED event.</li>
 *   <li>{@link RegistrationAlertNotifier} — sends the one alert.</li>
 * </ul>
 *
 * <p><b>This class no longer maps anything.</b> It used to parse the marker itself, call the
 * fee-type matcher itself, and then reach into the finished model to overwrite two fields the
 * mapper had already set. Mapping happened in two places, and the model was only true after the
 * orchestrator had finished editing it. Everything that interprets the document now sits behind
 * {@link EInvoiceMappingPort}, and what is left here is orchestration: decide, persist, notify.
 *
 * <p><b>One alert per invoice.</b> Defects are found during mapping and again during rule
 * evaluation, but nothing is dispatched until both are done — an invoice with four problems
 * earns one email listing four things, not four emails. The row is written first, so the alert
 * can quote the id of something that already exists.
 *
 * <p><b>Attachments: uploaded wins, embedded is the fallback.</b> When the caller supplies files,
 * the copies embedded in the document body are ignored rather than merged. A sender who uploads
 * a corrected PDF while a superseded one is still embedded in the document means the upload;
 * merging would register both and leave a person to work out which one counts.
 */
public final class InvoiceRegistrationServiceImpl implements InvoiceRegistrationService {

  private static final System.Logger LOG =
      System.getLogger(InvoiceRegistrationServiceImpl.class.getName());

  private final EInvoiceMappingPort mappingPort;
  private final ValidationRegistry rules;
  private final InvoicePayableStore store;
  private final LifecycleEventPublisher lifecyclePublisher;
  private final RegistrationAlertNotifier alertNotifier;

  public InvoiceRegistrationServiceImpl(
      EInvoiceMappingPort mappingPort,
      ValidationRegistry rules,
      InvoicePayableStore store,
      LifecycleEventPublisher lifecyclePublisher,
      RegistrationAlertNotifier alertNotifier) {
    this.mappingPort = Objects.requireNonNull(mappingPort, "mappingPort");
    this.rules = Objects.requireNonNull(rules, "rules");
    this.store = Objects.requireNonNull(store, "store");
    this.lifecyclePublisher = Objects.requireNonNull(lifecyclePublisher, "lifecyclePublisher");
    this.alertNotifier = Objects.requireNonNull(alertNotifier, "alertNotifier");
  }

  /**
   * Register an e-invoice.
   *
   * @param eInvoice            the incoming document; must not be null
   * @param uploadedAttachments files uploaded alongside the request. When non-empty these are
   *                            the attachments and anything embedded in the document is ignored;
   *                            when empty, the embedded copies are used instead.
   * @return the outcome. The row was persisted either way.
   */
  @Override
  public RegistrationOutcome register(Invoice eInvoice,
                                      List<ExtractedAttachment> uploadedAttachments) {
    Objects.requireNonNull(eInvoice, "eInvoice");

    MappingResult mapped = runMapping(eInvoice);
    List<MappingError> errors = new ArrayList<>(mapped.errors());
    Attachments attachments = chooseAttachments(uploadedAttachments, mapped);

    runRules(new ValidationContext(
            mapped.marker().business(), mapped.marker(), eInvoice,
            mapped.model(), mapped.items(), attachments.files(), attachments.channel()),
        mapped.marker(), errors);

    RegistrationOutcome outcome = RegistrationOutcome.decide(errors);
    UUID rowId = persist(mapped, attachments, outcome);

    // A publisher failure is found after the verdict, so it cannot go into the list the verdict
    // was built from — that list has already been copied. It is folded onto the outcome instead,
    // which is what the alert is rendered from and what the caller gets back.
    outcome = outcome.withAdditionalError(publishLifecycle(outcome, rowId, eInvoice));
    sendAlert(outcome, rowId, eInvoice, mapped.marker());

    return outcome;
  }

  // ── Steps ─────────────────────────────────────────────────────────────────

  /**
   * The mapping port reports defects in its result rather than throwing, but an adapter is still
   * code, and a registration that vanishes because one threw is worse than one recorded failed.
   */
  private MappingResult runMapping(Invoice eInvoice) {
    try {
      return mappingPort.map(eInvoice);
    } catch (RuntimeException ex) {
      return new MappingResult(null, List.of(), List.of(), EInvoiceMarker.empty(), null, null,
          List.of(MappingError.of(ErrorCode.MAPPING_ERROR,
              "mapping port threw unexpectedly: " + ex.getMessage(), ex)));
    }
  }

  /** Uploaded files win outright; embedded ones are the fallback. */
  private static Attachments chooseAttachments(List<ExtractedAttachment> uploaded,
                                               MappingResult mapped) {
    if (uploaded != null && !uploaded.isEmpty()) {
      return new Attachments(List.copyOf(uploaded), AttachmentChannel.MULTIPART);
    }
    return new Attachments(mapped.embeddedAttachments(), AttachmentChannel.EINVOICE_BODY);
  }

  private record Attachments(List<ExtractedAttachment> files, AttachmentChannel channel) {

    /** The metadata rows, tagged with the channel that delivered them. */
    List<InvoiceDocumentPayable> documents() {
      return files.stream()
          .map(f -> InvoiceDocumentPayable.fromAttachment(f, channel.name()))
          .toList();
    }
  }

  /** Rules are contracted not to throw; this is defence in depth. */
  private void runRules(ValidationContext ctx, EInvoiceMarker marker, List<MappingError> errors) {
    for (ValidationRule rule : rules.rulesFor(marker.business())) {
      try {
        List<MappingError> ruleErrors = rule.check(ctx);
        if (ruleErrors != null) {
          errors.addAll(ruleErrors);
        }
      } catch (RuntimeException ex) {
        errors.add(MappingError.of(ErrorCode.MAPPING_ERROR,
            "rule '" + rule.id() + "' threw unexpectedly: " + ex.getMessage(), ex));
      }
    }
  }

  /** The rows are always written, success or failure. */
  private UUID persist(MappingResult mapped, Attachments attachments,
                       RegistrationOutcome outcome) {
    return store.persist(new PersistRequest(
        mapped.marker().business(),
        mapped.feeId(),
        mapped.feeType(),
        FLOW_EINVOICE,
        mapped.model(),
        mapped.items(),
        attachments.documents(),
        outcome));
  }

  /**
   * Queue the REFUSED / SUSPENDED event, when the outcome carries one.
   *
   * @return an error to fold onto the outcome, or {@code null} when nothing went wrong. A
   *         publisher failure must not unwind persistence — the row is already committed and
   *         correct — so it is reported rather than thrown.
   */
  private MappingError publishLifecycle(RegistrationOutcome outcome, UUID rowId,
                                        Invoice eInvoice) {
    if (outcome.lifecycleEvent() == null) {
      return null;
    }
    try {
      lifecyclePublisher.publish(new PendingLifecycleEvent(
          rowId, eInvoice.getId(), outcome.lifecycleEvent(),
          outcome.lifecycleReasonCode(), outcome.comment(), Instant.now()));
      return null;
    } catch (RuntimeException ex) {
      return MappingError.of(ErrorCode.MAPPING_ERROR,
          "lifecycle publisher failed: " + ex.getMessage(), ex);
    }
  }

  /** One comprehensive alert per failed invoice. */
  private void sendAlert(RegistrationOutcome outcome, UUID rowId,
                         Invoice eInvoice, EInvoiceMarker marker) {
    if (!outcome.hasErrors()) {
      return;
    }
    try {
      alertNotifier.notify(new RegistrationAlert(
          rowId, eInvoice.getId(), marker.business(), marker, outcome, Instant.now()));
    } catch (RuntimeException ex) {
      // Alerting failing must never fail the registration — the row is already durable.
      LOG.log(System.Logger.Level.WARNING,
          "alert notifier threw for row " + rowId + ": " + ex.getMessage(), ex);
    }
  }
}
