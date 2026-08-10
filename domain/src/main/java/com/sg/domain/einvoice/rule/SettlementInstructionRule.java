package com.sg.domain.einvoice.rule;

import com.sg.domaininterface.model.einvoice.error.ErrorCode;
import com.sg.domaininterface.model.einvoice.error.MappingError;
import com.sg.domaininterface.model.payableinvoice.InvoicePayable;
import com.sg.domaininterface.model.payableinvoice.InvoicePayableModel;
import com.sg.domaininterface.model.provider.SsiDetails;
import com.sg.domaininterface.port.thirdparty.ReferentialUnavailableException;
import com.sg.domaininterface.port.thirdparty.SsiReferentialService;
import com.sg.domaininterface.rule.einvoice.ValidationContext;
import com.sg.domaininterface.rule.einvoice.ValidationRule;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Does the account on the invoice match one SG has agreed with this provider?
 *
 * <p><b>A rule, not a mapping step.</b> It produces a verdict — {@code MATCHED} or
 * {@code UNMATCHED} on {@code ssi_status} — and a verdict is what rules are for. It also means the
 * check is switchable per business and fee category like every other, which matters: settlement
 * matching is meaningful where invoices are paid out and noise where they are internal recharges.
 *
 * <p><b>The account details it compares had to exist first.</b> Until
 * {@code PaymentMeansMapper} was written the payable's three settlement fields were always null,
 * so this comparison could only ever have said {@code UNMATCHED} — and would have read as every
 * supplier in the estate having sent bad bank details.
 *
 * <p><b>Matching is deliberately conservative.</b> The account code must match exactly, ignoring
 * punctuation and case, before either the bank name or the BIC is even looked at. An account
 * confirmed by name alone is an account confirmed by a string two unrelated banks can share.
 *
 * <p><b>An unmatched account does not refuse the invoice.</b> The row is registered and
 * {@code ssi_status} is what holds settlement, exactly as on the manual path. Bank details that
 * disagree are frequently ours to reconcile, not the sender's to resend.
 */
public final class SettlementInstructionRule implements ValidationRule {

  static final String MATCHED = "MATCHED";
  static final String UNMATCHED = "UNMATCHED";

  /**
   * The fee category that requires an instruction to exist at all.
   *
   * <p>Everywhere else, no instruction on file is an onboarding state rather than a defect. For
   * this one, paired with the client type below, it is the condition the manual path singles out.
   */
  private static final String SSI_REQUIRED_FEE_CATEGORY = "42";

  private static final String SSI_REQUIRED_CLIENT_TYPE = "SGM_BILLING";

  /** Anything that is not a letter or a digit; IBANs are quoted with spaces as often as without. */
  private static final String NON_ALPHANUMERIC = "[^a-zA-Z0-9]";

  /** BICs are quoted with and without the branch filler, and the two are the same institution. */
  private static final String BRANCH_FILLER = "XXX";

  private final SsiReferentialService referential;

  public SettlementInstructionRule(SsiReferentialService referential) {
    this.referential = Objects.requireNonNull(referential, "referential");
  }

  @Override
  public List<MappingError> check(ValidationContext ctx) {
    InvoicePayableModel model = ctx.model();
    if (model == null || model.getInvoicePayable() == null) {
      return List.of();
    }
    InvoicePayable payable = model.getInvoicePayable();

    List<SsiDetails> onFile;
    try {
      onFile = referential.find(model.getProviderId(), model.getCurrency(),
          model.getSgEntity(), model.getFeeCategory());
    } catch (ReferentialUnavailableException ex) {
      // Leaving ssi_status unset says the comparison did not happen. Writing UNMATCHED would say
      // it happened and disagreed, which is what stops a payment — an outage must not do that.
      return List.of(MappingError.of(ErrorCode.ENRICHMENT_UNAVAILABLE,
          "settlement instructions unavailable for provider " + model.getProviderId()
              + ": " + ex.getMessage(), ex));
    }

    // Set before the search, so a rule that finds nothing still leaves a recorded verdict.
    model.setSsiStatus(UNMATCHED);

    if (onFile == null || onFile.isEmpty()) {
      return requiresInstruction(model, payable)
          ? List.of(MappingError.of(ErrorCode.SETTLEMENT_DETAILS_MISSING,
              "no settlement instruction on file for provider " + model.getProviderId()
                  + " in " + model.getCurrency()))
          : List.of();
    }

    for (SsiDetails candidate : onFile) {
      if (matches(payable, candidate)) {
        model.setSsiStatus(MATCHED);
        return List.of();
      }
    }

    return List.of(MappingError.of(ErrorCode.SETTLEMENT_DETAILS_UNMATCHED,
        "the account on the invoice (" + payable.getSsiAccountCode() + ") matches none of the "
            + onFile.size() + " instruction(s) on file for provider " + model.getProviderId()));
  }

  /**
   * Whether an absent instruction is a defect here.
   *
   * <p>Narrow on purpose: it reproduces the one combination the manual path singles out. Note that
   * {@code clientType} is not currently mapped from the e-invoice, so this is inert on that path
   * until it is — stated rather than quietly widened, because broadening the condition would apply
   * a policy to fee categories that never had it.
   */
  private static boolean requiresInstruction(InvoicePayableModel model, InvoicePayable payable) {
    return SSI_REQUIRED_FEE_CATEGORY.equals(model.getFeeCategory())
        && SSI_REQUIRED_CLIENT_TYPE.equalsIgnoreCase(payable.getClientType());
  }

  /**
   * One instruction against the invoice.
   *
   * <p>The account code gates everything. Beyond it, either the bank name or the BIC confirms the
   * match — a provider quoting only one of the two is normal, and requiring both would leave
   * correctly-instructed invoices unsettled.
   */
  private static boolean matches(InvoicePayable payable, SsiDetails candidate) {
    if (candidate == null || !sameAccount(payable.getSsiAccountCode(),
        candidate.accountNumber())) {
      return false;
    }
    return sameBankName(payable.getSsiBankDetail(), candidate.bankName())
        || sameSwift(payable.getSsiSwiftCode(), candidate.swiftCode());
  }

  private static boolean sameAccount(String invoiceAccount, String onFile) {
    if (invoiceAccount == null || onFile == null) {
      return false;
    }
    return normalise(invoiceAccount).equals(normalise(onFile));
  }

  private static boolean sameBankName(String invoiceBank, String onFile) {
    return invoiceBank != null && onFile != null && invoiceBank.equalsIgnoreCase(onFile);
  }

  /** {@code BNPAFRPPXXX} and {@code BNPAFRPP} are the same institution. */
  private static boolean sameSwift(String invoiceSwift, String onFile) {
    if (invoiceSwift == null || onFile == null) {
      return false;
    }
    return stripFiller(invoiceSwift).equalsIgnoreCase(stripFiller(onFile));
  }

  private static String stripFiller(String bic) {
    String trimmed = bic.trim();
    return trimmed.toUpperCase(Locale.ROOT).endsWith(BRANCH_FILLER)
        ? trimmed.substring(0, trimmed.length() - BRANCH_FILLER.length())
        : trimmed;
  }

  private static String normalise(String value) {
    return value.replaceAll(NON_ALPHANUMERIC, "").trim().toUpperCase(Locale.ROOT);
  }
}
