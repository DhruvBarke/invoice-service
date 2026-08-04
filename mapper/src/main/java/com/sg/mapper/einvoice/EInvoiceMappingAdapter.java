package com.sg.mapper.einvoice;

import com.sg.domaininterface.model.einvoice.EInvoiceMarker;
import com.sg.domaininterface.model.einvoice.EInvoiceMarkerParser;
import com.sg.domaininterface.model.einvoice.error.ErrorCode;
import com.sg.domaininterface.model.einvoice.error.MappingError;
import com.sg.domaininterface.model.invoice.ExtractedAttachment;
import com.sg.domaininterface.model.invoice.Invoice;
import com.sg.domaininterface.port.einvoice.EInvoiceMappingPort;
import com.sg.domaininterface.port.in.PartyRegistrationUnavailableException;
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
 *   <li>Write the resolved fee identity onto the model — {@code feeCategory} and
 *       {@code feeCategoryCode}.</li>
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
    String endpointValue = extractEndpointValue(eInvoice);
    EInvoiceMarker marker = EInvoiceMarkerParser.parse(endpointValue);

    if (endpointValue == null || endpointValue.isBlank()) {
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
      errors.add(MappingError.of(ErrorCode.FEETYPE_UNRESOLVED,
          "unresolved fee type '" + marker.feeType() + "': "
              + (reason == null ? "no reason available" : reason)));
      return null;
    } catch (RuntimeException ex) {
      // resolveOrNull should not throw for well-formed input, but the referential behind it can.
      errors.add(MappingError.of(ErrorCode.FEETYPE_UNRESOLVED,
          "fee-type provider failure: " + ex.getMessage(), ex));
      return null;
    }
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
    mapped.model().setFeeCategory(feeMatch.feeType());
    mapped.model().getInvoicePayable().setFeeCategoryCode(feeMatch.feeId());
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
   * Reach into the e-invoice for the receiver endpoint value.
   *
   * <p>The invoice itself is non-null — {@link #map} rejects null before anything reads it — so
   * only the nested elements need guarding.
   */
  private static String extractEndpointValue(Invoice inv) {
    if (inv.getAccountingCustomerParty() == null) return null;
    var party = inv.getAccountingCustomerParty().getParty();
    if (party == null || party.getEndpointId() == null) return null;
    return party.getEndpointId().getValue();
  }
}
