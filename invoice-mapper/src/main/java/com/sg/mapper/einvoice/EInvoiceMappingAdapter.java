package com.sg.mapper.einvoice;

import static com.sg.mapper.einvoice.Constant.ELECTRONIC_BROKER_FEE_CATEGORY_ID;
import static com.sg.mapper.einvoice.Constant.PRINCIPAL_FEE_CATEGORY_ID;
import static com.sg.mapper.einvoice.Constant.RECON_NOT_APPLICABLE;
import static com.sg.mapper.einvoice.Constant.RECON_TO_BE_PROCESSED;

import com.sg.domaininterface.model.einvoice.EInvoiceMarker;
import com.sg.domaininterface.model.einvoice.EInvoiceMarkerParser;
import com.sg.domaininterface.model.einvoice.error.ErrorCode;
import com.sg.domaininterface.model.einvoice.error.MappingError;
import com.sg.domaininterface.model.invoice.ExtractedAttachment;
import com.sg.domaininterface.model.invoice.Invoice;
import com.sg.domaininterface.model.payableinvoice.InvoicePayableModel;
import com.sg.domaininterface.model.payableinvoice.InvoicePayable;
import com.sg.domaininterface.port.out.EInvoiceMappingPort;
import com.sg.domaininterface.port.out.PartyRegistrationUnavailableException;
import com.sg.mapper.einvoice.EInvoiceFacadeMapper.MappedResult;
import com.sg.mapper.einvoice.FeeTypeMatcher.FeeTypeMatch;
import com.sg.mapper.einvoice.MultipartExtractionService.Result;
import com.sg.mapper.einvoice.MultipartExtractionService.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The mapping stack behind {@link EInvoiceMappingPort}: everything that reads the inbound
 * document, in one place.
 *
 * <p>Five things happen here, in order, and none of them abort the others:
 *
 * <ol>
 *   <li>Parse the receiver endpoint marker into {@link EInvoiceMarker}.</li>
 *   <li>Resolve its fee-type token through {@link FeeTypeMatcher} into a {@code (feeId, feeType)}
 *       pair.</li>
 *   <li>Map the document into an {@code InvoicePayableModel} plus lines.</li>
 *   <li>Write the resolved fee identity onto the model, and the reconciliation verdict that
 *       follows from it.</li>
 *   <li>Pull the base64 attachments out of the document body.</li>
 * </ol>
 *
 * <p><b>Why the fee matcher belongs here.</b> Steps 2 and 4 used to live in the orchestrator,
 * which called the matcher and then reached into a model the mapper had already finished to
 * overwrite two fields on it. Resolving what the sender's fee token means, and recording that
 * meaning on the payable, is mapping — the same kind of work as turning their currency code into
 * a currency. Splitting it across a module boundary meant no single component could answer "what
 * did this document say", and the model was only true after a second component had edited it.
 *
 * <p><b>Nothing here throws for a bad document.</b> Every defect becomes a {@link MappingError}
 * in the result and mapping carries on. A malformed marker and an unresolvable fee type are both
 * reported on the first attempt; aborting at the first would have the sender fix one thing,
 * resubmit, and discover the next. Exceptions are still caught — a referential being down is not
 * the sender's fault and gets its own code — but they are converted, not propagated.
 *
 * <p><b>Nothing here sends anything.</b> No alert, no row. The result is a value the caller
 * turns into exactly one notification. Publishing per-defect from inside the mapping would mean
 * a four-problem invoice generating four emails, and a mapping that failed at step 5 having
 * already reported success on steps 1 to 4.
 */
public final class EInvoiceMappingAdapter implements EInvoiceMappingPort {

  private static final System.Logger LOG =
      System.getLogger(EInvoiceMappingAdapter.class.getName());

  private final EInvoiceFacadeMapper facadeMapper;
  private final FeeTypeMatcher feeTypeMatcher;
  private final MultipartExtractionService extractor;

  public EInvoiceMappingAdapter(EInvoiceFacadeMapper facadeMapper,
                                FeeTypeMatcher feeTypeMatcher,
                                MultipartExtractionService extractor) {
    this.facadeMapper = Objects.requireNonNull(facadeMapper, "facadeMapper");
    this.feeTypeMatcher = Objects.requireNonNull(feeTypeMatcher, "feeTypeMatcher");
    this.extractor = Objects.requireNonNull(extractor, "extractor");
  }

  @Override
  public MappingResult map(Invoice eInvoice) {
    Objects.requireNonNull(eInvoice, "eInvoice");
    List<MappingError> errors = new ArrayList<>();

    EInvoiceMarker marker = parseMarker(eInvoice, errors);
    FeeTypeMatch feeMatch = resolveFeeType(marker, errors);
    MappedResult mapped = runMapper(eInvoice, errors);
    seedFeeIdentity(mapped, feeMatch);
    List<ExtractedAttachment> attachments = extractAttachments(eInvoice, errors);

    // The raw token when nothing matched it: the row should record what the sender actually
    // said, not go blank because the referential had no entry for it.
    String feeType = feeMatch != null ? feeMatch.feeType() : marker.feeType();
    String feeId = feeMatch != null ? feeMatch.feeId() : null;

    return new MappingResult(
        mapped.model(), mapped.items(), attachments, marker, feeId, feeType, errors);
  }

  // ── Steps ─────────────────────────────────────────────────────────────────

  /** Parse the receiver endpoint marker, recording any structural defect. */
  private static EInvoiceMarker parseMarker(Invoice eInvoice, List<MappingError> errors) {
    String markerValue = extractMarkerValue(eInvoice);
    EInvoiceMarker marker = EInvoiceMarkerParser.parse(markerValue);

    if (markerValue == null || markerValue.isBlank()) {
      errors.add(MappingError.of(ErrorCode.MARKER_MALFORMED,
          "accountingCustomerParty.party.endpointId.value is null or blank"));
      return marker;
    }
    if (marker.business() == null) {
      errors.add(MappingError.of(ErrorCode.BUSINESS_UNKNOWN,
          "business token unresolved in routing marker '" + markerValue + "'"));
    }
    if (marker.feeType() == null) {
      errors.add(MappingError.of(ErrorCode.MARKER_MALFORMED,
          "fee-category tail missing from routing marker '" + markerValue + "'"));
    }
    return marker;
  }

  /** Resolve the fee-type token against the referential. */
  private FeeTypeMatch resolveFeeType(EInvoiceMarker marker, List<MappingError> errors) {
    if (marker.feeType() == null) {
      // Already reported as MARKER_MALFORMED; a second error for the same defect would make one
      // problem look like two in the alert.
      return null;
    }
    try {
      FeeTypeMatch match = feeTypeMatcher.resolveOrNull(marker.feeType());
      if (match != null) {
        return match;
      }
      String reason = feeTypeMatcher.explainFailure(marker.feeType());
      // "matched nothing" and "matched several" are different problems with different fixes,
      // and the sender is the one who has to act on whichever it is.
      errors.add(MappingError.of(codeFor(reason),
          "could not resolve fee type '" + marker.feeType() + "': "
              + (reason == null ? "no reason available" : reason)));
      return null;
    } catch (RuntimeException ex) {
      // resolveOrNull should not throw for well-formed input, but the referential behind it can.
      errors.add(MappingError.of(ErrorCode.FEETYPE_UNRESOLVED,
          "fee-type provider failure: " + ex.getMessage(), ex));
      return null;
    }
  }

  /**
   * Which fee-type failure this is.
   *
   * <p>Read off the matcher's own explanation rather than given a second API to ask: the
   * matcher already distinguishes the two internally and says so in the text, and a parallel
   * boolean would be a second thing to keep in step with it.
   */
  private static ErrorCode codeFor(String explanation) {
    return explanation != null && explanation.toLowerCase(java.util.Locale.ROOT).contains("ambiguous")
        ? ErrorCode.FEETYPE_AMBIGUOUS
        : ErrorCode.FEETYPE_UNRESOLVED;
  }

  /** Run the mapping stack; the party lookup happens inside it. */
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
   * Record the resolved fee identity on the payable.
   *
   * <p>No null check on the nested payable: {@link EInvoiceFacadeMapper#toInvoicePayable} sets
   * one on every model it returns, and returns a null model rather than a half-built one when it
   * cannot. Guarding here would suggest a third state the mapper does not produce.
   */
  private static void seedFeeIdentity(MappedResult mapped, FeeTypeMatch feeMatch) {
    if (feeMatch == null || mapped.model() == null) {
      return;
    }
    InvoicePayableModel model = mapped.model();
    InvoicePayable payable = model.getInvoicePayable();

    // The referential answers feeId -> feeCategory. Both halves are recorded, in the three places
    // the existing rows use them:
    //
    //   t_invoice_payable.fee_category   <- model.feeCategory   = the ID
    //   invoice_payable.feeBdrId (jsonb) <- the ID, as a number
    //   invoice_payable.feeCategory      <- the NAME
    //
    // The id lands on the column called fee_category, and the name lands on the json field of the
    // same name. That reads backwards and is not a mistake: it is what every existing row holds
    // (fee_category "11" / feeCategory "Brokerage Principal"), and matching it is the whole point.
    // Writing the name into the column would make the e-invoicing rows the only ones a query on
    // that column could not find.
    model.setFeeCategory(feeMatch.feeId());
    payable.setFeeCategory(feeMatch.feeType());
    payable.setFeeBdrId(numericFeeId(feeMatch.feeId()));

    model.setReconProcess(reconProcessFor(feeMatch.feeId()));
  }

  /**
   * Whether this invoice goes to trade reconciliation.
   *
   * <p>Two fee categories reconcile against trades; everything else is settled on the invoice
   * alone. The manual path decides this at registration and the column is what reconciliation
   * selects on, so a row that leaves it null is one reconciliation never picks up — invisibly,
   * because nothing else reads the field to notice it is missing.
   *
   * <p>Only reached when the fee type resolved. An unresolved fee category leaves this null rather
   * than defaulting to "not applicable": the invoice is being refused anyway, and writing a
   * verdict derived from a fee identity we do not have would be a guess that outlives the refusal.
   */
  private static String reconProcessFor(String feeId) {
    return ELECTRONIC_BROKER_FEE_CATEGORY_ID.equals(feeId) || PRINCIPAL_FEE_CATEGORY_ID.equals(feeId)
        ? RECON_TO_BE_PROCESSED
        : RECON_NOT_APPLICABLE;
  }

  /**
   * The fee id as the number {@code feeBdrId} holds, or null when it is not one.
   *
   * <p>{@code feeBdrId} is an {@code Integer} because that is what the existing jsonb contains —
   * {@code "feeBdrId": 11}, not {@code "11"} — and writing a string there would give the column
   * two shapes for readers to handle.
   *
   * <p><b>Logged, not raised.</b> A non-numeric fee id means the referential changed shape, which
   * is true for every invoice at once: turning it into a per-invoice error would refuse or alert
   * on the whole inbound flow for one configuration problem. The id itself still reaches
   * {@code fee_category}, which is the column anything queries, so the row remains findable.
   */
  private static Integer numericFeeId(String feeId) {
    if (feeId == null || feeId.isBlank()) {
      return null;
    }
    try {
      return Integer.valueOf(feeId.trim());
    } catch (NumberFormatException e) {
      LOG.log(System.Logger.Level.WARNING,
          "fee id '" + feeId + "' is not numeric, so feeBdrId is left unset. Every invoice "
              + "resolving to this fee type is affected — check the fee referential.");
      return null;
    }
  }

  /**
   * Pull the base64 attachments out of the document body.
   *
   * <p>Uses the detailed form so a file that failed its magic-byte or base64 check is reported
   * rather than silently dropped. A corrupt attachment and an absent one look identical in the
   * plain extraction, and they are not the same conversation to have with a sender.
   */
  private List<ExtractedAttachment> extractAttachments(Invoice eInvoice,
                                                       List<MappingError> errors) {
    List<ExtractedAttachment> out = new ArrayList<>();
    try {
      for (Result r : extractor.extractDetailed(eInvoice)) {
        if (r.status() == Status.OK) {
          out.add(r.attachment());
        } else {
          errors.add(MappingError.of(ErrorCode.MISSING_ATTACHMENT,
              "embedded attachment '" + r.filename() + "' rejected: " + r.status()));
        }
      }
    } catch (RuntimeException ex) {
      // The extractor should not throw; this keeps one bad attachment from killing the run.
      errors.add(MappingError.of(ErrorCode.MAPPING_ERROR,
          "attachment extractor failed: " + ex.getMessage(), ex));
    }
    return out;
  }

  /**
   * The receiver's routing marker: {@code <siren>_<BUSINESS>_<FEETYPE>}.
   *
   * <p>Read from {@code EndpointID} and nowhere else. {@code PartyLegalEntity.CompanyID} holds
   * the bare SIREN — the same nine digits with no business and no fee type — so preferring it
   * silently reduces every marker to its first token: the business never resolves, the fee type
   * never resolves, and every invoice is refused as malformed while the document plainly
   * contains what was needed.
   *
   * <p>The invoice itself is non-null — {@link #map} rejects null before anything reads it — so
   * only the nested elements need guarding.
   */
  private static String extractMarkerValue(Invoice inv) {
    if (inv.getAccountingCustomerParty() == null) return null;
    var party = inv.getAccountingCustomerParty().getParty();
    if (party == null || party.getEndpointId() == null) return null;
    return party.getEndpointId().getValue();
  }

}
