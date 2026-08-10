package com.sg.mapper.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sg.domaininterface.model.invoice.CodedValue;
import com.sg.domaininterface.model.invoice.FinancialInstitution;
import com.sg.domaininterface.model.invoice.FinancialInstitutionBranch;
import com.sg.domaininterface.model.invoice.Invoice;
import com.sg.domaininterface.model.invoice.PayeeFinancialAccount;
import com.sg.domaininterface.model.invoice.PaymentMeans;
import com.sg.domaininterface.model.payableinvoice.InvoicePayable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading the settlement block out of the document.
 *
 * <p>The account code and the BIC carry most of the weight: they are what the settlement-
 * instruction comparison matches on, and a field left null there is indistinguishable from a
 * supplier whose details genuinely disagree with the ones on file.
 */
class PaymentMeansMapperTest {

  private static PayeeFinancialAccount account(String iban, String name, String bic,
                                               String institutionBic) {
    PayeeFinancialAccount a = new PayeeFinancialAccount();
    a.setId(iban);
    a.setName(name);
    if (bic != null || institutionBic != null) {
      FinancialInstitutionBranch branch = new FinancialInstitutionBranch();
      branch.setId(bic);
      if (institutionBic != null) {
        branch.setFinancialInstitution(new FinancialInstitution(institutionBic));
      }
      a.setFinancialInstitutionBranch(branch);
    }
    return a;
  }

  private static Invoice invoiceWith(PaymentMeans... means) {
    Invoice inv = new Invoice();
    inv.setPaymentMeans(new ArrayList<>(Arrays.asList(means)));
    return inv;
  }

  private static PaymentMeans means(String code, PayeeFinancialAccount... accounts) {
    PaymentMeans m = new PaymentMeans();
    if (code != null) {
      m.setPaymentMeansCode(CodedValue.fromString(code));
    }
    m.setPayeeFinancialAccount(new ArrayList<>(Arrays.asList(accounts)));
    return m;
  }

  @Test
  @DisplayName("the account, the bank and the method all come across")
  void fullBlock() {
    Invoice inv = invoiceWith(means("30",
        account("FR7630006000011234567890189", "EUROCLEAR FRANCE", "BNPAFRPP", null)));
    inv.setDueDate(LocalDate.of(2026, 5, 14));

    InvoicePayable payable = new InvoicePayable();
    PaymentMeansMapper.apply(inv, payable);

    assertEquals("FR7630006000011234567890189", payable.getSsiAccountCode());
    assertEquals("EUROCLEAR FRANCE", payable.getSsiBankDetail());
    assertEquals("BNPAFRPP", payable.getSsiSwiftCode());
    assertEquals("30", payable.getPaymentMethod());
    assertEquals(LocalDate.of(2026, 5, 14), payable.getPaymentDueDate());
  }

  @Test
  @DisplayName("the BIC is read from the branch, or from the institution beneath it")
  void bicIsReadFromEitherShape() {
    // EN 16931 puts BT-86 on the branch; UBL 2.1 originally put it on the institution, and
    // senders still emit both. Reading only one shape drops the identifier for half of them.
    InvoicePayable onBranch = new InvoicePayable();
    PaymentMeansMapper.apply(
        invoiceWith(means("30", account("FR76", null, "BNPAFRPP", null))), onBranch);
    assertEquals("BNPAFRPP", onBranch.getSsiSwiftCode());

    InvoicePayable onInstitution = new InvoicePayable();
    PaymentMeansMapper.apply(
        invoiceWith(means("30", account("FR76", null, null, "SOGEFRPP"))), onInstitution);
    assertEquals("SOGEFRPP", onInstitution.getSsiSwiftCode());

    // A branch that carries both: the branch's own id is the one EN 16931 asks for.
    InvoicePayable both = new InvoicePayable();
    PaymentMeansMapper.apply(
        invoiceWith(means("30", account("FR76", null, "BNPAFRPP", "SOGEFRPP"))), both);
    assertEquals("BNPAFRPP", both.getSsiSwiftCode());
  }

  @Test
  @DisplayName("a branch with no identifier at all leaves the swift code null")
  void branchWithoutAnyIdentifier() {
    PayeeFinancialAccount a = account("FR76", null, null, null);
    FinancialInstitutionBranch empty = new FinancialInstitutionBranch();
    a.setFinancialInstitutionBranch(empty);

    InvoicePayable payable = new InvoicePayable();
    PaymentMeansMapper.apply(invoiceWith(means("30", a)), payable);

    assertNull(payable.getSsiSwiftCode());
  }

  @Test
  @DisplayName("the first payment means wins")
  void firstMeansWins() {
    // Two payment means are alternatives, not a split. Merging them would take the IBAN from one
    // and the BIC from the other and describe an account that does not exist.
    Invoice inv = invoiceWith(
        means("30", account("FR76-FIRST", null, "BNPAFRPP", null)),
        means("58", account("DE89-SECOND", null, "DEUTDEFF", null)));

    InvoicePayable payable = new InvoicePayable();
    PaymentMeansMapper.apply(inv, payable);

    assertEquals("FR76-FIRST", payable.getSsiAccountCode());
    assertEquals("BNPAFRPP", payable.getSsiSwiftCode());
    assertEquals("30", payable.getPaymentMethod());
  }

  @Test
  @DisplayName("the typed due date beats the stringly-typed one")
  void dueDatePreference() {
    PaymentMeans m = means("30", account("FR76", null, null, null));
    m.setPaymentDueDate("2026-06-30");

    Invoice withBoth = invoiceWith(m);
    withBoth.setDueDate(LocalDate.of(2026, 5, 14));
    InvoicePayable preferred = new InvoicePayable();
    PaymentMeansMapper.apply(withBoth, preferred);
    assertEquals(LocalDate.of(2026, 5, 14), preferred.getPaymentDueDate());

    // BT-9 absent: fall back to the older spelling rather than losing the date.
    InvoicePayable fallback = new InvoicePayable();
    PaymentMeansMapper.apply(invoiceWith(m), fallback);
    assertEquals(LocalDate.of(2026, 6, 30), fallback.getPaymentDueDate());
  }

  @Test
  @DisplayName("an unreadable due date is null, not a failure")
  void unparseableDueDateIsNull() {
    // A date nobody can read is not grounds to refuse an invoice, and throwing here would lose
    // the whole registration over a field that is advisory.
    PaymentMeans m = means("30", account("FR76", null, null, null));
    m.setPaymentDueDate("the end of next month");

    InvoicePayable payable = new InvoicePayable();
    PaymentMeansMapper.apply(invoiceWith(m), payable);

    assertNull(payable.getPaymentDueDate());
  }

  @Test
  @DisplayName("blank values become null rather than empty strings")
  void blanksBecomeNull() {
    // Null reads as "the supplier did not say"; an empty string reads as "the supplier said
    // something", which is what the comparison would then report as a mismatch.
    Invoice inv = invoiceWith(means("   ", account("  ", "  ", "   ", null)));

    InvoicePayable payable = new InvoicePayable();
    PaymentMeansMapper.apply(inv, payable);

    assertNull(payable.getSsiAccountCode());
    assertNull(payable.getSsiBankDetail());
    assertNull(payable.getSsiSwiftCode());
    assertNull(payable.getPaymentMethod());
  }

  @Test
  @DisplayName("surrounding whitespace is trimmed off the identifiers")
  void identifiersAreTrimmed() {
    Invoice inv = invoiceWith(means(" 30 ", account(" FR76 ", " ACME ", " BNPAFRPP ", null)));

    InvoicePayable payable = new InvoicePayable();
    PaymentMeansMapper.apply(inv, payable);

    // A trailing space is enough to fail an exact-match comparison that would otherwise pass.
    assertEquals("FR76", payable.getSsiAccountCode());
    assertEquals("ACME", payable.getSsiBankDetail());
    assertEquals("BNPAFRPP", payable.getSsiSwiftCode());
    assertEquals("30", payable.getPaymentMethod());
  }

  @Test
  @DisplayName("a document with no payment means leaves every field null")
  void noPaymentMeans() {
    InvoicePayable payable = new InvoicePayable();
    PaymentMeansMapper.apply(new Invoice(), payable);

    assertNull(payable.getSsiAccountCode());
    assertNull(payable.getSsiSwiftCode());
    assertNull(payable.getSsiBankDetail());
    assertNull(payable.getPaymentMethod());
    assertNull(payable.getPaymentDueDate());
  }

  @Test
  @DisplayName("payment means with no account still yields the method and the due date")
  void meansWithoutAccount() {
    Invoice inv = invoiceWith(means("30"));
    inv.setDueDate(LocalDate.of(2026, 5, 14));

    InvoicePayable payable = new InvoicePayable();
    PaymentMeansMapper.apply(inv, payable);

    assertEquals("30", payable.getPaymentMethod());
    assertEquals(LocalDate.of(2026, 5, 14), payable.getPaymentDueDate());
    assertNull(payable.getSsiAccountCode());
  }

  @Test
  @DisplayName("a null payment-means list is the same as an empty one")
  void nullPaymentMeansList() {
    Invoice inv = new Invoice();
    inv.setPaymentMeans(null);

    InvoicePayable payable = new InvoicePayable();
    PaymentMeansMapper.apply(inv, payable);

    assertNull(payable.getPaymentMethod());
  }

  @Test
  @DisplayName("a null document or a null payable is a no-op, not a failure")
  void nullArguments() {
    InvoicePayable payable = new InvoicePayable();
    PaymentMeansMapper.apply(null, payable);
    assertNull(payable.getSsiAccountCode());

    // No assertion possible on the second — the point is that it does not throw.
    PaymentMeansMapper.apply(invoiceWith(means("30")), null);
  }

  @Test
  @DisplayName("an empty account list behaves like no account")
  void emptyAccountList() {
    PaymentMeans m = new PaymentMeans();
    m.setPaymentMeansCode(CodedValue.fromString("30"));
    m.setPayeeFinancialAccount(List.of());

    InvoicePayable payable = new InvoicePayable();
    PaymentMeansMapper.apply(invoiceWith(m), payable);

    assertEquals("30", payable.getPaymentMethod());
    assertNull(payable.getSsiAccountCode());
  }

  @Test
  @DisplayName("a null account list behaves like no account")
  void nullAccountList() {
    PaymentMeans m = new PaymentMeans();
    m.setPaymentMeansCode(CodedValue.fromString("30"));
    m.setPayeeFinancialAccount(null);

    InvoicePayable payable = new InvoicePayable();
    PaymentMeansMapper.apply(invoiceWith(m), payable);

    assertNull(payable.getSsiAccountCode());
  }
}
