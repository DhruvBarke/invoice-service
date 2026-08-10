package com.sg.domain.einvoice.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.model.einvoice.EInvoiceMarker;
import com.sg.domaininterface.model.einvoice.error.ErrorCode;
import com.sg.domaininterface.model.einvoice.error.MappingError;
import com.sg.domaininterface.model.invoice.Invoice;
import com.sg.domaininterface.model.payableinvoice.InvoicePayable;
import com.sg.domaininterface.model.payableinvoice.InvoicePayableModel;
import com.sg.domaininterface.model.provider.SsiDetails;
import com.sg.domaininterface.port.thirdparty.ReferentialUnavailableException;
import com.sg.domaininterface.port.thirdparty.SsiReferentialService;
import com.sg.domaininterface.rule.einvoice.AttachmentChannel;
import com.sg.domaininterface.rule.einvoice.ValidationContext;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Matching the account on the invoice against the ones SG has agreed with the provider.
 *
 * <p>The account code gating everything else is the property worth protecting: an account
 * confirmed by bank name alone is an account confirmed by a string two unrelated banks can share,
 * and what follows a match is a payment.
 */
class SettlementInstructionRuleTest {

  private static final String IBAN = "FR76 3000 6000 0112 3456 7890 189";

  private static InvoicePayableModel model(String account, String bank, String swift) {
    InvoicePayable payable = new InvoicePayable();
    payable.setSsiAccountCode(account);
    payable.setSsiBankDetail(bank);
    payable.setSsiSwiftCode(swift);

    InvoicePayableModel m = new InvoicePayableModel();
    m.setInvoicePayable(payable);
    m.setProviderId("BDR-1");
    m.setCurrency("EUR");
    m.setSgEntity("552120222");
    m.setFeeCategory("F01");
    return m;
  }

  private static ValidationContext ctx(InvoicePayableModel model) {
    return new ValidationContext(Business.MARK, EInvoiceMarker.empty(), new Invoice(),
        model, List.of(), List.of(), AttachmentChannel.EINVOICE_BODY);
  }

  private static SsiReferentialService onFile(SsiDetails... details) {
    return (p, c, e, f) -> List.of(details);
  }

  private static boolean has(List<MappingError> errors, ErrorCode code) {
    return errors.stream().anyMatch(err -> err.code() == code);
  }

  // ── Matching ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("account plus bank name is a match")
  void matchOnBankName() {
    InvoicePayableModel m = model(IBAN, "BNP PARIBAS", null);

    List<MappingError> errors = new SettlementInstructionRule(
        onFile(new SsiDetails("FR7630006000011234567890189", "BNP Paribas", null)))
        .check(ctx(m));

    assertTrue(errors.isEmpty());
    assertEquals("MATCHED", m.getSsiStatus());
  }

  @Test
  @DisplayName("account plus swift is a match, filler and all")
  void matchOnSwift() {
    // BNPAFRPPXXX and BNPAFRPP are the same institution, and providers quote both.
    InvoicePayableModel m = model(IBAN, null, "BNPAFRPP");

    List<MappingError> errors = new SettlementInstructionRule(
        onFile(new SsiDetails("FR7630006000011234567890189", null, "BNPAFRPPXXX")))
        .check(ctx(m));

    assertTrue(errors.isEmpty());
    assertEquals("MATCHED", m.getSsiStatus());
  }

  @Test
  @DisplayName("punctuation and case in the account code are ignored")
  void accountIsNormalised() {
    // IBANs are quoted with spaces as often as without, and a space is not a different account.
    InvoicePayableModel m = model("fr76-3000.6000 0112 3456 7890 189", null, "BNPAFRPP");

    new SettlementInstructionRule(
        onFile(new SsiDetails("FR7630006000011234567890189", null, "BNPAFRPP")))
        .check(ctx(m));

    assertEquals("MATCHED", m.getSsiStatus());
  }

  @Test
  @DisplayName("the first matching instruction among several wins")
  void firstMatchWins() {
    InvoicePayableModel m = model(IBAN, null, "BNPAFRPP");

    List<MappingError> errors = new SettlementInstructionRule(onFile(
        new SsiDetails("DE89370400440532013000", null, "DEUTDEFF"),
        new SsiDetails("FR7630006000011234567890189", null, "BNPAFRPP")))
        .check(ctx(m));

    assertTrue(errors.isEmpty());
    assertEquals("MATCHED", m.getSsiStatus());
  }

  // ── Not matching ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("a matching bank name with a different account is not a match")
  void accountGatesEverything() {
    // The failure this ordering exists to prevent: a payment approved because two banks share a
    // name, into an account nobody agreed to.
    InvoicePayableModel m = model("FR7611111111111111111111111", "BNP PARIBAS", "BNPAFRPP");

    List<MappingError> errors = new SettlementInstructionRule(
        onFile(new SsiDetails("FR7630006000011234567890189", "BNP PARIBAS", "BNPAFRPP")))
        .check(ctx(m));

    assertTrue(has(errors, ErrorCode.SETTLEMENT_DETAILS_UNMATCHED));
    assertEquals("UNMATCHED", m.getSsiStatus());
  }

  @Test
  @DisplayName("a matching account with neither name nor swift agreeing is not a match")
  void accountAloneIsNotEnough() {
    InvoicePayableModel m = model(IBAN, "SOCIETE GENERALE", "SOGEFRPP");

    List<MappingError> errors = new SettlementInstructionRule(
        onFile(new SsiDetails("FR7630006000011234567890189", "BNP PARIBAS", "BNPAFRPP")))
        .check(ctx(m));

    assertTrue(has(errors, ErrorCode.SETTLEMENT_DETAILS_UNMATCHED));
    assertEquals("UNMATCHED", m.getSsiStatus());
  }

  @Test
  @DisplayName("a confirming field present on only one side confirms nothing")
  void halfPresentConfirmationsDoNotMatch() {
    // Providers routinely quote a bank name or a BIC but not both, on either side. Treating an
    // absent value as agreement would confirm an account on the strength of a field nobody
    // supplied — and what follows a confirmation is a payment.

    // Name on the invoice, none on file; BIC on file, none on the invoice.
    InvoicePayableModel nameOnly = model(IBAN, "BNP PARIBAS", null);
    assertTrue(has(new SettlementInstructionRule(
            onFile(new SsiDetails("FR7630006000011234567890189", null, "BNPAFRPP")))
            .check(ctx(nameOnly)), ErrorCode.SETTLEMENT_DETAILS_UNMATCHED));

    // The mirror image: BIC on the invoice, none on file.
    InvoicePayableModel swiftOnly = model(IBAN, "BNP PARIBAS", "BNPAFRPP");
    assertTrue(has(new SettlementInstructionRule(
            onFile(new SsiDetails("FR7630006000011234567890189", "SOCIETE GENERALE", null)))
            .check(ctx(swiftOnly)), ErrorCode.SETTLEMENT_DETAILS_UNMATCHED));
  }

  @Test
  @DisplayName("an invoice with no account details cannot match")
  void noAccountOnTheInvoice() {
    InvoicePayableModel m = model(null, null, null);

    List<MappingError> errors = new SettlementInstructionRule(
        onFile(new SsiDetails("FR7630006000011234567890189", "BNP PARIBAS", "BNPAFRPP")))
        .check(ctx(m));

    assertTrue(has(errors, ErrorCode.SETTLEMENT_DETAILS_UNMATCHED));
  }

  @Test
  @DisplayName("an instruction with no account number, or no instruction at all, is skipped")
  void unusableInstructionsAreSkipped() {
    InvoicePayableModel m = model(IBAN, null, "BNPAFRPP");

    List<MappingError> errors = new SettlementInstructionRule((p, c, e, f) -> {
      List<SsiDetails> mixed = new java.util.ArrayList<>();
      mixed.add(null);
      mixed.add(new SsiDetails(null, "BNP PARIBAS", "BNPAFRPP"));
      return mixed;
    }).check(ctx(m));

    assertTrue(has(errors, ErrorCode.SETTLEMENT_DETAILS_UNMATCHED));
    assertEquals("UNMATCHED", m.getSsiStatus());
  }

  @Test
  @DisplayName("an unmatched account does not refuse the invoice")
  void unmatchedIsAlertOnly() {
    // Bank details that disagree are frequently ours to reconcile, not the sender's to resend.
    // ssi_status is what holds settlement, exactly as on the manual path.
    assertNull(ErrorCode.SETTLEMENT_DETAILS_UNMATCHED.lifecycleEvent());
    assertNull(ErrorCode.SETTLEMENT_DETAILS_MISSING.lifecycleEvent());
  }

  // ── Nothing on file ───────────────────────────────────────────────────────

  @Test
  @DisplayName("no instructions on file is normally an onboarding state, not a defect")
  void nothingOnFileIsUsuallyQuiet() {
    InvoicePayableModel m = model(IBAN, null, "BNPAFRPP");

    List<MappingError> errors = new SettlementInstructionRule((p, c, e, f) -> List.of())
        .check(ctx(m));

    assertTrue(errors.isEmpty());
    assertEquals("UNMATCHED", m.getSsiStatus(),
        "the verdict is still recorded: nothing was matched");
  }

  @Test
  @DisplayName("for fee category 42 under SGM_BILLING, nothing on file is reported")
  void nothingOnFileWhereOneIsRequired() {
    // The one combination the manual path singles out. Reproduced narrowly rather than widened:
    // broadening it would apply a policy to fee categories that never had it.
    InvoicePayableModel m = model(IBAN, null, "BNPAFRPP");
    m.setFeeCategory("42");
    m.getInvoicePayable().setClientType("SGM_BILLING");

    List<MappingError> errors = new SettlementInstructionRule((p, c, e, f) -> List.of())
        .check(ctx(m));

    assertTrue(has(errors, ErrorCode.SETTLEMENT_DETAILS_MISSING));
  }

  @Test
  @DisplayName("fee category 42 with another client type is not reported")
  void feeCategory42AloneIsNotEnough() {
    InvoicePayableModel m = model(IBAN, null, "BNPAFRPP");
    m.setFeeCategory("42");
    m.getInvoicePayable().setClientType("OTHER");

    assertTrue(new SettlementInstructionRule((p, c, e, f) -> List.of()).check(ctx(m)).isEmpty());
  }

  @Test
  @DisplayName("a null response is treated as nothing on file")
  void nullResponseIsNothingOnFile() {
    InvoicePayableModel m = model(IBAN, null, "BNPAFRPP");

    assertTrue(new SettlementInstructionRule((p, c, e, f) -> null).check(ctx(m)).isEmpty());
    assertEquals("UNMATCHED", m.getSsiStatus());
  }

  // ── Failures ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("an unreachable referential leaves the status unset rather than unmatched")
  void outageDoesNotHoldThePayment() {
    // UNMATCHED says the comparison happened and disagreed, which is what stops a payment. An
    // outage must not be able to say that about every invoice in flight.
    InvoicePayableModel m = model(IBAN, null, "BNPAFRPP");

    List<MappingError> errors = new SettlementInstructionRule((p, c, e, f) -> {
      throw new ReferentialUnavailableException("ssi", "down", true, null);
    }).check(ctx(m));

    assertTrue(has(errors, ErrorCode.ENRICHMENT_UNAVAILABLE));
    assertNull(m.getSsiStatus());
  }

  // ── Contract ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("a model that never mapped is skipped, not blamed")
  void unmappedModelIsSkipped() {
    SettlementInstructionRule rule = new SettlementInstructionRule(onFile());

    assertTrue(rule.check(new ValidationContext(Business.MARK, EInvoiceMarker.empty(),
        new Invoice(), null, List.of(), List.of(), AttachmentChannel.EINVOICE_BODY)).isEmpty());

    InvoicePayableModel noPayload = new InvoicePayableModel();
    assertTrue(rule.check(ctx(noPayload)).isEmpty());
  }

  @Test
  @DisplayName("the rule id is what the yaml switches on")
  void stableId() {
    // Renaming the class must not silently disable the rule everywhere it is configured.
    assertEquals("settlement-instruction", new SettlementInstructionRule(onFile()).id());
  }

  @Test
  @DisplayName("the referential is mandatory")
  void referentialIsMandatory() {
    assertThrows(NullPointerException.class, () -> new SettlementInstructionRule(null));
  }
}
