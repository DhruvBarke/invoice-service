package com.example.invoice.mapper.einvoice;

import static com.example.invoice.mapper.einvoice.Constant.*;

import com.example.invoice.mapper.einvoice.model.invoice.CodedValue;
import com.example.invoice.mapper.einvoice.model.invoice.CurrencyAmount;
import com.example.invoice.mapper.einvoice.model.invoice.LegalMonetaryTotal;
import com.example.invoice.mapper.einvoice.model.invoice.SchemeID;
import com.example.invoice.mapper.einvoice.model.invoice.TaxCategory;
import com.example.invoice.mapper.einvoice.model.invoice.TaxSchemeRef;
import com.example.invoice.mapper.einvoice.model.invoice.TaxSubtotal;
import com.example.invoice.mapper.einvoice.model.invoice.TaxTotal;
import java.math.BigDecimal;
import java.util.List;

/**
 * Helpers for the amount block of {@code Invoice}.
 *
 * <p>Ported from A's {@code AmountMapper} — was a MapStruct {@code @Mapper} interface with
 * default methods; now a {@code final} utility class with static methods. Two shape changes vs
 * A on the type side:
 *
 * <ul>
 *   <li>feesone {@code Amount(value, currencyID)} → in-repo {@link CurrencyAmount}
 *       ({@code currencyID}, {@code value}) — same fields, different order.</li>
 *   <li>feesone {@code TaxScheme(id, idValue)} → in-repo {@link TaxSchemeRef} (single {@code id}
 *       field, {@code idValue} is a computed getter). The {@code setIdValue} calls in A's
 *       mapper are therefore dropped — setting {@code id} with a {@link SchemeID} of
 *       {@code value=DEFAULT_VAT_SCHEME} covers both reads.</li>
 * </ul>
 *
 * <p>Legal-monetary-total convention (unchanged from A):
 * <ul>
 *   <li>{@code lineExtensionAmount} = payable.amount minus vat</li>
 *   <li>{@code taxExclusiveAmount}  = {@code lineExtensionAmount}</li>
 *   <li>{@code taxInclusiveAmount}  = payable.amount</li>
 *   <li>{@code payableAmount}        = {@code taxInclusiveAmount}</li>
 * </ul>
 */
public final class AmountMapper {

  private AmountMapper() {}

  public static CurrencyAmount toAmount(BigDecimal value, String currency) {
    if (value == null) return null;
    CurrencyAmount a = new CurrencyAmount();
    a.setValue(value);
    a.setCurrencyID(currency);
    return a;
  }

  public static BigDecimal value(CurrencyAmount a) {
    return a == null ? null : a.getValue();
  }

  public static CodedValue toCodedCurrency(String currency) {
    if (currency == null) return null;
    CodedValue cv = new CodedValue();
    cv.setValue(currency);
    return cv;
  }

  public static String fromCodedCurrency(CodedValue cv) {
    return cv == null ? null : cv.getValue();
  }

  /**
   * Build a {@link LegalMonetaryTotal} from the payable totals.
   *
   * @param totalInclTax payable amount (incl VAT). Mandatory; returns null if absent.
   * @param vatAmount    optional VAT amount; defaults to ZERO when null.
   * @param currency     ISO-4217 currency.
   */
  public static LegalMonetaryTotal toLegalMonetaryTotal(
      BigDecimal totalInclTax, BigDecimal vatAmount, String currency) {
    if (totalInclTax == null) return null;
    BigDecimal vat = vatAmount == null ? BigDecimal.ZERO : vatAmount;
    BigDecimal lineExt = totalInclTax.subtract(vat);
    LegalMonetaryTotal m = new LegalMonetaryTotal();
    m.setLineExtensionAmount(toAmount(lineExt, currency));
    m.setTaxExclusiveAmount(toAmount(lineExt, currency));
    m.setTaxInclusiveAmount(toAmount(totalInclTax, currency));
    m.setPayableAmount(toAmount(totalInclTax, currency));
    return m;
  }

  /**
   * Build a single-subtotal {@link TaxTotal} list using the supplied {@code vatAmount} and
   * {@code vatRate}. Returns an empty list when both are null, mirroring the EN16931
   * expectation that the block is optional when no VAT applies.
   */
  public static List<TaxTotal> toTaxTotal(
      BigDecimal vatAmount, BigDecimal vatRate, BigDecimal taxableBase, String currency) {
    if (vatAmount == null && vatRate == null) return List.of();
    BigDecimal vat = vatAmount == null ? BigDecimal.ZERO : vatAmount;
    BigDecimal pct = vatRate == null ? BigDecimal.ZERO : vatRate;

    SchemeID schemeId = new SchemeID();
    schemeId.setValue(DEFAULT_VAT_SCHEME);
    TaxSchemeRef scheme = new TaxSchemeRef();
    scheme.setId(schemeId);

    SchemeID catId = new SchemeID();
    catId.setValue(DEFAULT_TAX_CATEGORY);
    TaxCategory category = new TaxCategory();
    category.setId(catId);
    category.setPercent(pct);
    category.setTaxScheme(scheme);

    TaxSubtotal subtotal = new TaxSubtotal();
    subtotal.setTaxableAmount(toAmount(taxableBase, currency));
    subtotal.setTaxAmount(toAmount(vat, currency));
    subtotal.setTaxCategory(category);

    TaxTotal total = new TaxTotal();
    total.setTaxAmount(toAmount(vat, currency));
    total.setTaxSubtotal(List.of(subtotal));
    return List.of(total);
  }

  /** einvoice → payable: pull the first subtotal's VAT amount, if any. */
  public static BigDecimal firstVatAmount(List<TaxTotal> totals) {
    if (totals == null || totals.isEmpty()) return null;
    TaxTotal t = totals.get(0);
    if (t.getTaxSubtotal() == null || t.getTaxSubtotal().isEmpty()) return null;
    CurrencyAmount a = t.getTaxSubtotal().get(0).getTaxAmount();
    return a == null ? null : a.getValue();
  }

  /** einvoice → payable: pull the first subtotal's VAT rate, if any. */
  public static BigDecimal firstVatRate(List<TaxTotal> totals) {
    if (totals == null || totals.isEmpty()) return null;
    TaxTotal t = totals.get(0);
    if (t.getTaxSubtotal() == null || t.getTaxSubtotal().isEmpty()) return null;
    TaxCategory cat = t.getTaxSubtotal().get(0).getTaxCategory();
    return cat == null ? null : cat.getPercent();
  }
}
