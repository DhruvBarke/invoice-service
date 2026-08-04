package com.example.invoice.service.domain.model.invoice;

import lombok.*;
import java.math.BigDecimal;

/** Monetary amount with currency. Matches UBL {currencyID, value} structure. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CurrencyAmount {
    private String currencyID;
    private BigDecimal value;

    public static CurrencyAmount fromString(String val) {
        return new CurrencyAmount(null, new BigDecimal(val));
    }

    public static CurrencyAmount of(BigDecimal value, String currency) {
        return new CurrencyAmount(currency, value);
    }
    public static CurrencyAmount eur(BigDecimal value) {
        return new CurrencyAmount("EUR", value);
    }
}
