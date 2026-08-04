package com.sg.domaininterface.model.report;

import java.math.BigDecimal;
import lombok.*;

/**
 * Amount carrying a {@code @CurrencyCode} attribute. Used for
 * {@code MonetaryTotal/TaxAmount} (TT-52 + TT-202) where the spec requires
 * the currency to be declared inline.
 *
 * <p>Other monetary amounts in Flux 10 inherit their currency from
 * {@code Invoice/CurrencyCode} (TT-22) and are modelled as plain
 * {@link BigDecimal}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class CurrencyAmount {
  /** Decimal value of the amount. */
  private BigDecimal value;
  /** ISO 4217 currency code carried as {@code @CurrencyCode} on the wire. */
  private String currencyCode;
}
