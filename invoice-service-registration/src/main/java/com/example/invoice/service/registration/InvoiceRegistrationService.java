package com.example.invoice.service.registration;

import com.example.invoice.mapper.einvoice.EInvoiceFacadeMapper;
import com.example.invoice.mapper.einvoice.EInvoiceFacadeMapper.MappedResult;
import com.example.invoice.mapper.einvoice.FeeTypeMatcher;
import com.example.invoice.mapper.einvoice.FeeTypeMatcher.FeeTypeMatch;
import com.example.invoice.mapper.einvoice.MultipartExtractionService;
import com.example.invoice.mapper.einvoice.MultipartExtractionService.ExtractedAttachment;
import com.example.invoice.mapper.einvoice.model.invoice.Invoice;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoiceDocumentPayable;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

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

  private static final System.Logger LOG =
      System.getLogger(InvoiceRegistrationService.class.getName());

  /**
   * The only producer this pipeline is. Written to {@code t_invoice_payable.invoice_flow}, the
   * column shared with manual capture and SGAi — which is what makes "e-invoicing only" a
   * question the data can answer. Manual / SGAi registrations bypass this service entirely.
   */
  static final String INVOICE_FLOW_EINVOICE = "EINVOICE";

  /** {@code incoming_line} values: which channel carried the document. */
  static final String LINE_EINVOICE_BODY = "EINVOICE_BODY";

  static final String LINE_MULTIPART = "MULTIPART";

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

    EInvoiceMarker marker = parseMarker(eInvoice, errors);
    FeeTypeMatch feeMatch = resolveFeeType(marker, errors);
    MappedResult mapped = runMapper(eInvoice, errors);
    seedFeeCategory(mapped, feeMatch);
    List<ExtractedAttachment> jsonAttachments = extractJsonAttachments(eInvoice, errors);

    runRules(new ValidationContext(marker.business(), marker, eInvoice,
        mapped.model(), mapped.items(), jsonAttachments, mp), marker, errors);

    RegistrationOutcome outcome = RegistrationOutcome.decide(errors);
    UUID rowId = persist(marker, feeMatch, mapped, jsonAttachments, mp, outcome);

    publishLifecycle(outcome, rowId, eInvoice, errors);
    sendAlert(outcome, rowId, eInvoice, marker);

    return outcome;
  }

  // ── Pipeline steps ────────────────────────────────────────────────────────

  /** Step 1 — parse the receiver endpoint marker, recording any structural defect. */
  private EInvoiceMarker parseMarker(Invoice eInvoice, List<MappingError> errors) {
    String endpointValue = extractEndpointValue(eInvoice);
    EInvoiceMarker marker = EInvoiceMarkerParser.parse(endpointValue);

    boolean absent = endpointValue == null || endpointValue.isBlank();
    if (absent) {
      errors.add(MappingError.of(ErrorCode.MARKER_MALFORMED,
          "accountingCustomerParty.party.endpointId.value is null or blank"));
      return marker;
    }
    if (marker.business() == null) {
      errors.add(MappingError.of(ErrorCode.BUSINESS_UNKNOWN,
          "business token unresolved in endpoint marker '" + endpointValue + "'"));
    }
    if (marker.feeType() == null) {
      errors.add(MappingError.of(ErrorCode.MARKER_MALFORMED,
          "fee-type tail missing from endpoint marker '" + endpointValue + "'"));
    }
    return marker;
  }

  /** Step 2 — resolve the fee-type token against the referential. */
  private FeeTypeMatch resolveFeeType(EInvoiceMarker marker, List<MappingError> errors) {
    if (marker.feeType() == null) {
      return null;
    }
    try {
      FeeTypeMatch match = feeTypeMatcher.resolveOrNull(marker.feeType());
      if (match != null) {
        return match;
      }
      String reason = feeTypeMatcher.explainFailure(marker.feeType());
      errors.add(MappingError.of(ErrorCode.FEETYPE_UNRESOLVED,
          "unresolved fee type '" + marker.feeType() + "': "
              + (reason == null ? "no reason available" : reason)));
      return null;
    } catch (RuntimeException ex) {
      // resolveOrNull shouldn't throw for a well-formed input, but the provider might.
      errors.add(MappingError.of(ErrorCode.FEETYPE_UNRESOLVED,
          "fee-type provider failure: " + ex.getMessage(), ex));
      return null;
    }
  }

  /** Step 3 — run the mapping stack; the party lookup happens inside it. */
  private MappedResult runMapper(Invoice eInvoice, List<MappingError> errors) {
    try {
      return facadeMapper.toInvoicePayable(eInvoice);
    } catch (PartyRegistrationUnavailableException ex) {
      errors.add(MappingError.of(ErrorCode.PARTY_LOOKUP_FAILED,
          "party registration lookup failed: " + ex.getMessage(), ex));
    } catch (RuntimeException ex) {
      errors.add(MappingError.of(ErrorCode.MAPPING_ERROR,
          "unhandled mapping exception: " + ex.getMessage(), ex));
    }
    return new MappedResult(null, List.of());
  }

  /**
   * Step 4 — copy the resolved fee identity onto the mapped model.
   *
   * <p>No null check on the nested payable: {@link EInvoiceFacadeMapper#toInvoicePayable} sets
   * one on every model it returns, and returns a null model rather than a half-built one when
   * it cannot. Guarding here would suggest a third state that the mapper does not produce.
   */
  private static void seedFeeCategory(MappedResult mapped, FeeTypeMatch feeMatch) {
    if (feeMatch == null || mapped.model() == null) {
      return;
    }
    mapped.model().setFeeCategory(feeMatch.feeType());
    mapped.model().getInvoicePayable().setFeeCategoryCode(feeMatch.feeId());
  }

  /** Step 5 — pull base64 attachments out of the e-invoice body. */
  private List<ExtractedAttachment> extractJsonAttachments(Invoice eInvoice,
                                                           List<MappingError> errors) {
    try {
      return multipartExtractor.extract(eInvoice);
    } catch (RuntimeException ex) {
      // The extractor should not throw; this keeps one bad attachment from killing the run.
      errors.add(MappingError.of(ErrorCode.MAPPING_ERROR,
          "attachment extractor failed: " + ex.getMessage(), ex));
      return List.of();
    }
  }

  /** Step 6 — run every rule configured for the resolved business. */
  private void runRules(ValidationContext ctx, EInvoiceMarker marker, List<MappingError> errors) {
    for (ValidationRule rule : rules.rulesFor(marker.business())) {
      try {
        List<MappingError> ruleErrors = rule.check(ctx);
        if (ruleErrors != null) {
          errors.addAll(ruleErrors);
        }
      } catch (RuntimeException ex) {
        // The contract says rules don't throw; this is defence in depth.
        errors.add(MappingError.of(ErrorCode.MAPPING_ERROR,
            "rule '" + rule.id() + "' threw unexpectedly: " + ex.getMessage(), ex));
      }
    }
  }

  /**
   * Step 8 — the rows are always written, success or failure.
   *
   * <p>The store mints {@code invoiceReference} and writes it back onto the model, its items and
   * its documents, so the alert and the lifecycle payload quote the value the row actually has.
   */
  private UUID persist(EInvoiceMarker marker, FeeTypeMatch feeMatch, MappedResult mapped,
                       List<ExtractedAttachment> jsonAttachments,
                       List<ExtractedAttachment> multipartAttachments,
                       RegistrationOutcome outcome) {
    return store.persist(new PersistRequest(
        marker.business(),
        feeMatch == null ? null : feeMatch.feeId(),
        feeMatch == null ? marker.feeType() : feeMatch.feeType(),
        INVOICE_FLOW_EINVOICE,
        mapped.model(),
        mapped.items(),
        toDocuments(jsonAttachments, multipartAttachments, outcome),
        outcome));
  }

  /**
   * Turn both attachment channels into document rows.
   *
   * <p><b>Metadata only — the bytes stop here.</b> Content belongs in SGDoc, and
   * {@code sgDocId} is left null because nothing in this service uploads it yet. A row with a
   * null handle is the honest record that the document arrived and its content is not yet
   * retrievable; inventing a handle, or dropping the row because there is no handle, would both
   * lose that.
   *
   * <p>The two channels stay distinguishable in {@code incoming_line} all the way to the row.
   * "No attachment" reads very differently depending on whether the sender embedded nothing or
   * the upload alongside the request was empty, and the attachment rules key on that difference.
   */
  private static List<InvoiceDocumentPayable> toDocuments(
      List<ExtractedAttachment> jsonAttachments,
      List<ExtractedAttachment> multipartAttachments,
      RegistrationOutcome outcome) {

    List<InvoiceDocumentPayable> documents =
        new ArrayList<>(jsonAttachments.size() + multipartAttachments.size());
    for (ExtractedAttachment a : jsonAttachments) {
      documents.add(toDocument(a, LINE_EINVOICE_BODY, outcome));
    }
    for (ExtractedAttachment a : multipartAttachments) {
      documents.add(toDocument(a, LINE_MULTIPART, outcome));
    }
    return documents;
  }

  private static InvoiceDocumentPayable toDocument(ExtractedAttachment a, String incomingLine,
                                                   RegistrationOutcome outcome) {
    return InvoiceDocumentPayable.builder()
        .documentName(a.filename())
        .documentType(documentTypeOf(a.filename()))
        .format(a.mimeType())
        .incomingLine(incomingLine)
        .registrationType(INVOICE_FLOW_EINVOICE)
        // Whether the invoice this document belongs to registered cleanly. A document attached
        // to a refused invoice is still a document worth keeping, and this is what says so.
        .registrationStatus(!outcome.hasErrors())
        .build();
  }

  /** Which kind of attachment this is, so the trade-file rule's verdict is legible afterwards. */
  static String documentTypeOf(String filename) {
    if (filename == null) {
      return "OTHER";
    }
    String lower = filename.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".pdf")) {
      return "PDF";
    }
    if (lower.endsWith(".csv") || lower.endsWith(".xlsx")) {
      return "TRADE_FILE";
    }
    return "OTHER";
  }

  /** Step 9 — queue the REFUSED / SUSPENDED event, when the outcome carries one. */
  private void publishLifecycle(RegistrationOutcome outcome, UUID rowId,
                                Invoice eInvoice, List<MappingError> errors) {
    if (outcome.lifecycleEvent() == null) {
      return;
    }
    try {
      lifecyclePublisher.publish(new PendingLifecycleEvent(
          rowId, eInvoice.getId(), outcome.lifecycleEvent(),
          outcome.lifecycleReasonCode(), outcome.comment(), Instant.now()));
    } catch (RuntimeException ex) {
      // A publisher failure must not unwind persistence; it rides along in the alert instead.
      errors.add(MappingError.of(ErrorCode.MAPPING_ERROR,
          "lifecycle publisher failed: " + ex.getMessage(), ex));
    }
  }

  /** Step 10 — one comprehensive alert per failed invoice. */
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

  /**
   * Reach into the e-invoice for the receiver endpoint value.
   *
   * <p>The invoice itself is non-null — {@link #register} rejects null before anything reads it
   * — so only the nested elements need guarding.
   */
  private static String extractEndpointValue(Invoice inv) {
    if (inv.getAccountingCustomerParty() == null) return null;
    var party = inv.getAccountingCustomerParty().getParty();
    if (party == null || party.getEndpointId() == null) return null;
    return party.getEndpointId().getValue();
  }
}
