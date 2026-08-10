package com.sg.mapper.einvoice;

import com.sg.domaininterface.model.invoice.CodedValue;
import com.sg.domaininterface.model.invoice.FinancialInstitutionBranch;
import com.sg.domaininterface.model.invoice.Invoice;
import com.sg.domaininterface.model.invoice.PayeeFinancialAccount;
import com.sg.domaininterface.model.invoice.PaymentMeans;
import com.sg.domaininterface.model.payableinvoice.InvoicePayable;
import java.time.LocalDate;
import java.util.List;

/**
 * The settlement block: where the supplier asked to be paid, and by when.
 *
 * <p><b>Why this exists.</b> {@code compareSSIDetails} on the manual path matches the invoice's
 * {@code ssiAccountCode}, {@code ssiSwiftCode} and {@code ssiBankDetail} against the settlement
 * instructions held for the provider, and refuses to settle when they disagree. On the e-invoicing
 * path all three were left null, so that comparison could only ever return "unmatched" — and it
 * would have looked like a data problem at the supplier rather than a mapping that never ran. The
 * account details are in the document; they were simply not being read out of it.
 *
 * <p><b>The first payment means wins.</b> UBL allows several, and a supplier offering two ways to
 * be paid is offering alternatives rather than describing a split. Merging them would produce an
 * IBAN from one and a BIC from another — an account that does not exist.
 *
 * <p>Written as static methods on a final class, matching {@link AmountMapper} and
 * {@link PartyMapper}: there is no state here, and a bean would only be a bean.
 */
public final class PaymentMeansMapper {

  private PaymentMeansMapper() {}

  /**
   * Copy the settlement details from the document onto the payable.
   *
   * <p>Absent elements leave their fields null rather than blank. Null reads as "the supplier did
   * not say", which is what the SSI comparison needs to tell apart from "the supplier said
   * something that does not match" — an empty string would look like the latter.
   */
  public static void apply(Invoice inv, InvoicePayable payable) {
    if (inv == null || payable == null) {
      return;
    }
    PaymentMeans means = first(inv.getPaymentMeans());

    payable.setPaymentMethod(codeValue(means == null ? null : means.getPaymentMeansCode()));
    payable.setPaymentDueDate(dueDate(inv, means));

    PayeeFinancialAccount account = means == null ? null : first(means.getPayeeFinancialAccount());
    if (account == null) {
      return;
    }
    payable.setSsiAccountCode(trimToNull(account.getId()));
    // BT-85 is the payment ACCOUNT name, not the bank's. It is mapped here because it is the only
    // name the document carries and dropping it would leave the field permanently null, but the
    // swift arm of the comparison is the reliable one: a name that does not match simply falls
    // through to it, and both arms are only reached once the account code has already matched
    // exactly.
    payable.setSsiBankDetail(trimToNull(account.getName()));
    payable.setSsiSwiftCode(bic(account.getFinancialInstitutionBranch()));
  }

  /**
   * The BIC.
   *
   * <p>EN 16931 puts it on the branch (BT-86); UBL 2.1 originally put it on the institution
   * underneath, and senders still emit both shapes. Reading only one would drop the identifier for
   * half of them, and a missing BIC costs the comparison its reliable arm.
   */
  private static String bic(FinancialInstitutionBranch branch) {
    if (branch == null) {
      return null;
    }
    String onBranch = trimToNull(branch.getId());
    if (onBranch != null) {
      return onBranch;
    }
    return branch.getFinancialInstitution() == null
        ? null
        : trimToNull(branch.getFinancialInstitution().getId());
  }

  /**
   * When payment is due.
   *
   * <p>{@code Invoice.dueDate} (BT-9) is preferred: it is the typed field and the one EN 16931
   * asks for. {@code PaymentMeans.paymentDueDate} is the older stringly-typed spelling of the same
   * thing, kept as a fallback for senders still emitting it, and unparseable text there yields
   * null rather than a failure — a due date nobody can read is not grounds to refuse an invoice.
   */
  private static LocalDate dueDate(Invoice inv, PaymentMeans means) {
    if (inv.getDueDate() != null) {
      return inv.getDueDate();
    }
    return means == null ? null : DateMapper.parse(means.getPaymentDueDate());
  }

  private static String codeValue(CodedValue coded) {
    return coded == null ? null : trimToNull(coded.getValue());
  }

  private static <T> T first(List<T> values) {
    return values == null || values.isEmpty() ? null : values.get(0);
  }

  private static String trimToNull(String s) {
    if (s == null) {
      return null;
    }
    String trimmed = s.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
