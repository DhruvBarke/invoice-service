package com.sg.mapper.einvoice;

import com.sg.domaininterface.model.invoice.CodedValue;
import static com.sg.mapper.einvoice.Constant.*;

/**
 * Maps between the einvoice UNTDID 1001 {@link CodedValue} on {@code Invoice.invoiceTypeCode}
 * and the canonical {@code InvoicePayableModel.invoiceType} label.
 *
 * <p>Ported from A's {@code InvoiceTypeMapper} — was a MapStruct {@code @Mapper} interface with
 * default + {@code @Named} methods; now a {@code final} utility class with static methods.
 *
 * <ul>
 *   <li>DEBIT     ⇄ "380"  Commercial invoice</li>
 *   <li>CREDIT    ⇄ "381"  Credit note</li>
 *   <li>CORRECTED ⇄ "384"  Corrected invoice</li>
 * </ul>
 */
public final class InvoiceTypeMapper {

  private InvoiceTypeMapper() {}

  /** einvoice → payable: unwrap the {@link CodedValue} and translate to the canonical label. */
  public static String toInvoiceType(CodedValue invoiceTypeCode) {
    if (invoiceTypeCode == null || invoiceTypeCode.getValue() == null) return UNKNOWN;
    String raw = invoiceTypeCode.getValue();
    if (INVOICE_TYPE_DEBIT.equals(raw)) return DEBIT;
    if (INVOICE_TYPE_CREDIT.equals(raw)) return CREDIT;
    if (INVOICE_TYPE_CORRECTED.equals(raw)) return CORRECTED;
    return UNKNOWN;
  }

  /**
   * payable → einvoice: wrap the canonical label in a {@link CodedValue} with the matching
   * UNTDID 1001 code. Unknown / null inputs default to the DEBIT code (380).
   */
  public static CodedValue toInvoiceTypeCode(String invoiceType) {
    String code;
    if (invoiceType == null) code = INVOICE_TYPE_DEBIT;
    else switch (invoiceType.toUpperCase()) {
      case DEBIT -> code = INVOICE_TYPE_DEBIT;
      case CREDIT -> code = INVOICE_TYPE_CREDIT;
      case CORRECTED -> code = INVOICE_TYPE_CORRECTED;
      default -> code = INVOICE_TYPE_DEBIT;
    }
    CodedValue cv = new CodedValue();
    cv.setValue(code);
    return cv;
  }
}
