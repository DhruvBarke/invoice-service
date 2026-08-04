package com.example.invoice.mapper.report;

import com.example.invoice.service.domain.model.report.CurrencyAmount;
import com.example.invoice.service.domain.model.report.MonetaryTotal;
import com.example.invoice.service.domain.model.report.TaxCategory;
import com.example.invoice.service.domain.model.report.TaxSubTotal;
import java.math.BigDecimal;
import java.util.List;

/**
 * Builds the invoice-level totals block: {@link MonetaryTotal} (TG-22) plus one or more
 * {@link TaxSubTotal} (TG-23).
 *
 * <p>Ported from A's {@code ReportTotalsMapper} — was a MapStruct {@code @Mapper} interface;
 * now a {@code final} utility class with static methods.
 *
 * <p>{@code InvoicePayable} carries a single VAT rate / single VAT amount per invoice, so this
 * mapper produces exactly one {@code TaxSubTotal} entry per call. If the registration source
 * ever splits VAT across rates, this is the spot to fan out.
 */
public final class ReportTotalsMapper {

  /** Default UNCL 5305 VAT category code. */
  public static final String DEFAULT_TAX_CATEGORY_CODE = "S";

  private ReportTotalsMapper() {}

  /** Build {@link MonetaryTotal} from invoice-level totals. */
  public static MonetaryTotal toMonetaryTotal(
      BigDecimal amountIncludingTax, BigDecimal vatAmount, String currency) {
    if (amountIncludingTax == null) return null;
    BigDecimal vat = vatAmount != null ? vatAmount : BigDecimal.ZERO;
    BigDecimal taxExclusive = amountIncludingTax.subtract(vat);
    return MonetaryTotal.builder()
        .taxExclusiveAmount(taxExclusive)
        .taxAmount(CurrencyAmount.builder()
            .value(vat)
            .currencyCode(currency)
            .build())
        .build();
  }

  /** Build a single-entry list of {@link TaxSubTotal}. */
  public static List<TaxSubTotal> toTaxSubTotals(
      BigDecimal taxableAmount, BigDecimal vatAmount, BigDecimal vatRate) {
    if (taxableAmount == null && vatAmount == null && vatRate == null) {
      return List.of();
    }
    return List.of(TaxSubTotal.builder()
        .taxableAmount(taxableAmount != null ? taxableAmount : BigDecimal.ZERO)
        .taxAmount(vatAmount != null ? vatAmount : BigDecimal.ZERO)
        .taxCategory(TaxCategory.builder()
            .code(DEFAULT_TAX_CATEGORY_CODE)
            .percent(vatRate != null ? vatRate : BigDecimal.ZERO)
            .build())
        .build());
  }
}
