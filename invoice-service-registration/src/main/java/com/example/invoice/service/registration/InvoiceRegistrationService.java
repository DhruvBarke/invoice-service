package com.example.invoice.service.registration;

import com.example.invoice.mapper.einvoice.EInvoiceFacadeMapper;
import com.example.invoice.mapper.einvoice.EInvoiceFacadeMapper.MappedResult;
import com.example.invoice.mapper.einvoice.FeeTypeMatcher;
import com.example.invoice.mapper.einvoice.FeeTypeMatcher.FeeTypeMatch;
import com.example.invoice.mapper.einvoice.MultipartExtractionService;
import com.example.invoice.mapper.einvoice.MultipartExtractionService.ExtractedAttachment;
import com.example.invoice.mapper.einvoice.model.invoice.Invoice;
import com.example.invoice.service.registration.error.ErrorCode;
import com.example.invoice.service.registration.error.MappingError;
import com.example.invoice.service.registration.error.RegistrationOutcome;
import com.example.invoice.service.registration.port.InvoicePayableStore;
import com.example.invoice.service.registration.port.InvoicePayableStore.PersistRequest;
import com.example.invoice.service.registration.port.LifecycleEventPublisher;
import com.example.invoice.service.registration.port.LifecycleEventPublisher.PendingLifecycleEvent;
import com.example.invoice.service.registration.port.RegistrationAlertNotifier;
import com.example.invoice.service.registration.port.RegistrationAlertNotifier.RegistrationAlert;
import com.example.invoice.service.registration.rule.ValidationContext;
import com.example.invoice.service.registration.rule.ValidationRegistry;
import com.example.invoice.service.registration.rule.ValidationRule;
import com.example.invoice.service.domain.port.in.PartyRegistrationUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrator for e-invoice → InvoicePayable registration.
 *
 * <p>Assembled from six collaborators — none of them Spring-aware. Any deployment that can
 * supply the six ports can construct and drive this service:
 *
 * <ul>
 *   <li>{@link EInvoiceFacadeMapper} — the mapping stack (party lookup happens inside).</li>
 *   <li>{@link FeeTypeMatcher} — resolves the fee-type token to {@code (feeId, feeType)}.</li>
 *   <li>{@link MultipartExtractionService} — extracts base64 attachments from the e-invoice.</li>
 *   <li>{@link ValidationRegistry} — per-business rule set.</li>
 *   <li>{@link InvoicePayableStore} — writes the row.</li>
 *   <li>{@link LifecycleEventPublisher} — records the pending REFUSED / SUSPENDED event.</li>
 *   <li>{@link RegistrationAlertNotifier} — sends the human alert.</li>
 * </ul>
 *
 * <p><b>Flow.</b>
 * <ol>
 *   <li>Parse the accounting-customer-party endpoint marker → {@link EInvoiceMarker}.</li>
 *   <li>Guard the marker: missing business → {@code BUSINESS_UNKNOWN}; missing fee type →
 *       {@code MARKER_MALFORMED}. These are recorded but do NOT abort — the mapper is still
 *       run so we capture whatever InvoicePayable it can produce.</li>
 *   <li>Resolve the fee type via {@link FeeTypeMatcher#resolveOrNull}. On success, seed the
 *       (feeId, feeType) so downstream persistence carries them. On failure, add
 *       {@code FEETYPE_UNRESOLVED} but continue.</li>
 *   <li>Run the mapper inside a try/catch — {@link PartyRegistrationUnavailableException}
 *       becomes {@code PARTY_LOOKUP_FAILED}; any other RuntimeException becomes
 *       {@code MAPPING_ERROR}. Both leave the model null.</li>
 *   <li>Extract JSON-body attachments; combine with the multipart-body attachments the caller
 *       hands in.</li>
 *   <li>Run every rule registered for the resolved business. Rules never throw; each returns
 *       a {@code List<MappingError>} that is accumulated.</li>
 *   <li>Decide the outcome via
 *       {@link RegistrationOutcome#decide(List)} — REFUSED &gt; SUSPENDED &gt; INCOMPLETE
 *       &gt; REGISTERED.</li>
 *   <li>Persist via {@link InvoicePayableStore}. The row always exists.</li>
 *   <li>If the outcome has a lifecycle event, publish it. If it has any errors, notify.</li>
 * </ol>
 *
 * <p><b>Point 7 of the spec — comprehensive error capture.</b> Every exception thrown by any
 * step (marker parse, fee-type resolve, party lookup, mapping) is caught, translated to an
 * {@link ErrorCode}, and folded into the outcome. The alert email lists all of them; the
 * row's {@code error_codes} JSONB stores all of them. Nothing goes to the console-only
 * channel.
 */
public final class InvoiceRegistrationService {

  private final EInvoiceFacadeMapper facadeMapper;
  private final FeeTypeMatcher feeTypeMatcher;
  private final MultipartExtractionService multipartExtractor;
  private final ValidationRegistry rules;
  private final InvoicePayableStore store;
  private final LifecycleEventPublisher lifecyclePublisher;
  private final RegistrationAlertNotifier alertNotifier;

  public InvoiceRegistrationService(
      EInvoiceFacadeMapper facadeMapper,
      FeeTypeMatcher feeTypeMatcher,
      MultipartExtractionService multipartExtractor,
      ValidationRegistry rules,
      InvoicePayableStore store,
      LifecycleEventPublisher lifecyclePublisher,
      RegistrationAlertNotifier alertNotifier) {
    this.facadeMapper = Objects.requireNonNull(facadeMapper, "facadeMapper");
    this.feeTypeMatcher = Objects.requireNonNull(feeTypeMatcher, "feeTypeMatcher");
    this.multipartExtractor = Objects.requireNonNull(multipartExtractor, "multipartExtractor");
    this.rules = Objects.requireNonNull(rules, "rules");
    this.store = Objects.requireNonNull(store, "store");
    this.lifecyclePublisher = Objects.requireNonNull(lifecyclePublisher, "lifecyclePublisher");
    this.alertNotifier = Objects.requireNonNull(alertNotifier, "alertNotifier");
  }

  /**
   * Register an e-invoice.
   *
   * @param eInvoice             the incoming e-invoice (mandatory, non-null)
   * @param multipartAttachments raw file uploads from the HTTP request; may be empty
   * @return the registration outcome — regardless of whether it fired errors, the row was
   *         persisted and its id is on the alert if one was sent
   */
  public RegistrationOutcome register(Invoice eInvoice, List<ExtractedAttachment> multipartAttachments) {
    Objects.requireNonNull(eInvoice, "eInvoice");
    List<ExtractedAttachment> mp = multipartAttachments == null ? List.of() : multipartAttachments;

    List<MappingError> errors = new ArrayList<>();

    // 1. Marker parse.
    String endpointValue = extractEndpointValue(eInvoice);
    EInvoiceMarker marker = EInvoiceMarkerParser.parse(endpointValue);

    if (endpointValue == null || endpointValue.isBlank()) {
      errors.add(MappingError.of(ErrorCode.MARKER_MALFORMED,
          "accountingCustomerParty.party.endpointId.value is null or blank"));
    } else if (marker.business() == null) {
      errors.add(MappingError.of(ErrorCode.BUSINESS_UNKNOWN,
          "business token unresolved in endpoint marker '" + endpointValue + "'"));
    }
    if (marker.feeType() == null && endpointValue != null && !endpointValue.isBlank()) {
      errors.add(MappingError.of(ErrorCode.MARKER_MALFORMED,
          "fee-type tail missing from endpoint marker '" + endpointValue + "'"));
    }

    // 2. Fee-type resolve.
    FeeTypeMatch feeMatch = null;
    if (marker.feeType() != null) {
      try {
        feeMatch = feeTypeMatcher.resolveOrNull(marker.feeType());
      } catch (RuntimeException ex) {
        // resolveOrNull shouldn't throw for a well-formed input, but the provider might.
        errors.add(MappingError.of(ErrorCode.FEETYPE_UNRESOLVED,
            "fee-type provider failure: " + ex.getMessage(), ex));
      }
      if (feeMatch == null) {
        String reason = feeTypeMatcher.explainFailure(marker.feeType());
        errors.add(MappingError.of(ErrorCode.FEETYPE_UNRESOLVED,
            "unresolved fee type '" + marker.feeType() + "': "
                + (reason == null ? "no reason available" : reason)));
      }
    }

    // 3. Mapping (party lookup happens inside).
    MappedResult mapped;
    try {
      mapped = facadeMapper.toInvoicePayable(eInvoice);
    } catch (PartyRegistrationUnavailableException ex) {
      errors.add(MappingError.of(ErrorCode.PARTY_LOOKUP_FAILED,
          "party registration lookup failed: " + ex.getMessage(), ex));
      mapped = new MappedResult(null, List.of());
    } catch (RuntimeException ex) {
      errors.add(MappingError.of(ErrorCode.MAPPING_ERROR,
          "unhandled mapping exception: " + ex.getMessage(), ex));
      mapped = new MappedResult(null, List.of());
    }

    // 4. Seed fee category / id on the model if the matcher produced one.
    if (feeMatch != null && mapped.model() != null) {
      mapped.model().setFeeCategory(feeMatch.feeType());
      if (mapped.model().getInvoicePayable() != null) {
        mapped.model().getInvoicePayable().setFeeCategoryCode(feeMatch.feeId());
      }
    }

    // 5. Extract JSON-body attachments.
    List<ExtractedAttachment> jsonAttachments;
    try {
      jsonAttachments = multipartExtractor.extract(eInvoice);
    } catch (RuntimeException ex) {
      // Extractor should not throw, but be defensive so the pipeline never dies here.
      errors.add(MappingError.of(ErrorCode.MAPPING_ERROR,
          "attachment extractor failed: " + ex.getMessage(), ex));
      jsonAttachments = List.of();
    }

    // 6. Run rules for the resolved business (nothing if business is null).
    ValidationContext ctx = new ValidationContext(
        marker.business(), marker, eInvoice,
        mapped.model(), mapped.items(),
        jsonAttachments, mp);
    for (ValidationRule rule : rules.rulesFor(marker.business())) {
      try {
        List<MappingError> ruleErrors = rule.check(ctx);
        if (ruleErrors != null) errors.addAll(ruleErrors);
      } catch (RuntimeException ex) {
        // Contract says rules don't throw; this is a defence-in-depth net.
        errors.add(MappingError.of(ErrorCode.MAPPING_ERROR,
            "rule '" + rule.id() + "' threw unexpectedly: " + ex.getMessage(), ex));
      }
    }

    // 7. Decide.
    RegistrationOutcome outcome = RegistrationOutcome.decide(errors);

    // 8. Persist.
    long rowId = store.persist(new PersistRequest(
        marker.business(),
        feeMatch != null ? feeMatch.feeId() : null,
        feeMatch != null ? feeMatch.feeType() : marker.feeType(),
        "EINVOICE",
        mapped.model(),
        mapped.items(),
        outcome));

    // 9. Lifecycle event.
    if (outcome.lifecycleEvent() != null) {
      try {
        lifecyclePublisher.publish(new PendingLifecycleEvent(
            rowId,
            eInvoice.getId(),
            outcome.lifecycleEvent(),
            outcome.lifecycleReasonCode(),
            outcome.comment(),
            java.time.Instant.now()));
      } catch (RuntimeException ex) {
        // Publisher failing shouldn't unwind persistence; log via the alert body.
        errors.add(MappingError.of(ErrorCode.MAPPING_ERROR,
            "lifecycle publisher failed: " + ex.getMessage(), ex));
      }
    }

    // 10. Alert.
    if (outcome.hasErrors()) {
      try {
        alertNotifier.notify(new RegistrationAlert(
            rowId,
            eInvoice.getId(),
            marker.business(),
            marker,
            outcome,
            java.time.Instant.now()));
      } catch (RuntimeException ex) {
        // Alert failing must never fail the registration.
        System.getLogger(InvoiceRegistrationService.class.getName())
            .log(System.Logger.Level.WARNING,
                "alert notifier threw for row " + rowId + ": " + ex.getMessage(), ex);
      }
    }

    return outcome;
  }

  /** Reach into the e-invoice for the receiver endpoint value. Null-safe. */
  private static String extractEndpointValue(Invoice inv) {
    if (inv == null || inv.getAccountingCustomerParty() == null) return null;
    var party = inv.getAccountingCustomerParty().getParty();
    if (party == null || party.getEndpointId() == null) return null;
    return party.getEndpointId().getValue();
  }
}
