package com.sg.domain.einvoice;

import com.sg.domain.einvoice.rule.ValidationRegistry;
import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.model.einvoice.EInvoiceMarker;
import com.sg.domaininterface.model.einvoice.error.ErrorCode;
import com.sg.domaininterface.model.einvoice.error.MappingError;
import com.sg.domaininterface.model.einvoice.error.RegistrationOutcome;
import com.sg.domaininterface.model.invoice.ExtractedAttachment;
import com.sg.domaininterface.model.invoice.Invoice;
import com.sg.domaininterface.model.payableinvoice.InvoiceDocumentPayable;
import com.sg.domaininterface.port.out.EInvoiceMappingPort.MappingResult;
import com.sg.domaininterface.port.out.EInvoiceMappingPort;
import com.sg.domaininterface.port.out.InvoiceEnrichmentPort;
import com.sg.domaininterface.port.out.InvoicePayableStore.PersistRequest;
import com.sg.domaininterface.port.out.InvoicePayableStore;
import com.sg.domaininterface.port.out.LifecycleEventPublisher.PendingLifecycleEvent;
import com.sg.domaininterface.port.out.LifecycleEventPublisher;
import com.sg.domaininterface.port.out.RegistrationAlertNotifier.RegistrationAlert;
import com.sg.domaininterface.port.thirdparty.ReferentialUnavailableException;
import com.sg.domaininterface.port.thirdparty.SgDocReferentialService;
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
 * <p>Six collaborators, none of them Spring-aware:
 *
 * <ul>
 *   <li>{@link EInvoiceMappingPort} — reads the document. Marker, fee identity, payable, lines
 *       and embedded attachments all come back from one call, along with whatever went wrong.</li>
 *   <li>{@link InvoiceEnrichmentPort} — fills the fields no document can carry: the euro
 *       amount, the re-attachment date and the activation flags.</li>
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
  private final InvoiceEnrichmentPort enricher;
  private final SgDocReferentialService documentStore;
  private final ValidationRegistry rules;
  private final InvoicePayableStore store;
  private final LifecycleEventPublisher lifecyclePublisher;
  private final RegistrationAlertNotifier alertNotifier;

  public InvoiceRegistrationServiceImpl(
      EInvoiceMappingPort mappingPort,
      InvoiceEnrichmentPort enricher,
      SgDocReferentialService documentStore,
      ValidationRegistry rules,
      InvoicePayableStore store,
      LifecycleEventPublisher lifecyclePublisher,
      RegistrationAlertNotifier alertNotifier) {
    this.mappingPort = Objects.requireNonNull(mappingPort, "mappingPort");
    this.enricher = Objects.requireNonNull(enricher, "enricher");
    this.documentStore = Objects.requireNonNull(documentStore, "documentStore");
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

    // Before the rules, so a rule that reads an enriched field sees the enriched value. The
    // settlement check is the one that does today; putting this after would have it decide on a
    // model that was still half-filled.
    errors.addAll(runEnrichment(mapped));

    runRules(new ValidationContext(
            mapped.marker().business(), mapped.marker(), eInvoice,
            mapped.model(), mapped.items(), attachments.files(), attachments.channel()),
        mapped.marker().business(), mapped.feeType(), errors);

    List<InvoiceDocumentPayable> documents = storeDocuments(attachments, eInvoice.getId(), errors);

    RegistrationOutcome outcome = RegistrationOutcome.decide(errors);

    // A persistence failure is deliberately NOT caught here: the row is what failed to write, so
    // there is nowhere to record it and nothing useful to return. It propagates to the caller as
    // whatever the store raised.
    UUID rowId = persist(mapped, documents, outcome);

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

  /**
   * Fill the fields the document could not supply.
   *
   * <p>The enricher is contracted to report rather than throw, for the same reason it exists: none
   * of what it fills decides whether the invoice is valid. This guard is for the case where it
   * throws anyway — losing a whole registration because a rate service misbehaved would be a much
   * worse outcome than a row with no euro amount on it.
   */
  private List<MappingError> runEnrichment(MappingResult mapped) {
    try {
      return enricher.enrich(mapped.model());
    } catch (RuntimeException ex) {
      return List.of(MappingError.of(ErrorCode.ENRICHMENT_UNAVAILABLE,
          "enrichment threw unexpectedly: " + ex.getMessage(), ex));
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

  private record Attachments(List<ExtractedAttachment> files, AttachmentChannel channel) {}

  /**
   * Push each attachment to the document store and record the handle it returns.
   *
   * <p>The content does not live in {@code t_invoice_document_payable} — that table holds
   * metadata and an {@code sg_doc_id}, and the bytes are in SGDoc. Without this step every
   * document row would carry a null handle forever and the content would be dropped on the
   * floor, while the row still looked like a document had been received.
   *
   * <p><b>The supplier's reference goes up, not SG's.</b> SG's reference does not exist yet —
   * the store mints it from a sequence when the row is written, which is after this. The
   * supplier's is what the document actually arrived with, and it is a correlation hint for the
   * document store rather than the authoritative link; that is
   * {@code t_invoice_document_payable.invoice_reference}, which the store stamps.
   *
   * <p><b>A failed upload does not fail the registration.</b> The row is written with a null
   * handle and an alert-only error, because refusing the sender's invoice for an outage on this
   * side would ask them to resend a document that was never the problem. A null handle is the
   * honest record that something arrived and is not yet retrievable — which is exactly what the
   * attachment rules need to tell apart from nothing having been sent.
   */
  private List<InvoiceDocumentPayable> storeDocuments(Attachments attachments,
                                                      String providerReference,
                                                      List<MappingError> errors) {
    List<InvoiceDocumentPayable> documents = new ArrayList<>(attachments.files().size());
    for (ExtractedAttachment file : attachments.files()) {
      InvoiceDocumentPayable document =
          InvoiceDocumentPayable.fromAttachment(file, attachments.channel().name());
      try {
        document.setSgDocId(documentStore.upload(file, providerReference));
      } catch (ReferentialUnavailableException ex) {
        errors.add(MappingError.of(ErrorCode.DOCUMENT_UPLOAD_FAILED,
            "could not store '" + file.filename() + "': " + ex.getMessage(), ex));
      } catch (RuntimeException ex) {
        // The port is contracted to raise ReferentialUnavailableException, but an adapter is
        // still code, and one that throws something else must not lose the whole registration.
        errors.add(MappingError.of(ErrorCode.DOCUMENT_UPLOAD_FAILED,
            "document store threw unexpectedly for '" + file.filename() + "': "
                + ex.getMessage(), ex));
      }
      documents.add(document);
    }
    return documents;
  }

  /** Rules are contracted not to throw; this is defence in depth. */
  /**
   * Run the rules configured for this business and fee category.
   *
   * <p>The resolved fee type is the scope key, falling back to the marker's raw token inside the
   * registry when nothing resolved. Scoping by business alone would mean a rule that only makes
   * sense for one kind of work — a trade file, say — either refuses every other kind or has to
   * be switched off for the whole business.
   */
  private void runRules(ValidationContext ctx, Business business, String feeCategory,
                        List<MappingError> errors) {
    for (ValidationRule rule : rules.rulesFor(business, feeCategory)) {
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
  private UUID persist(MappingResult mapped, List<InvoiceDocumentPayable> documents,
                       RegistrationOutcome outcome) {
    return store.persist(new PersistRequest(
        mapped.marker().business(),
        mapped.feeId(),
        mapped.feeType(),
        FLOW_EINVOICE,
        mapped.model(),
        mapped.items(),
        documents,
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
