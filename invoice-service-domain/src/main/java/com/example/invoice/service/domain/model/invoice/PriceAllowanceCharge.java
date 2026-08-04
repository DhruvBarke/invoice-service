package com.example.invoice.service.domain.model.invoice;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class PriceAllowanceCharge {
    private Boolean chargeIndicator;
    private String allowanceChargeReason;
    private String multiplierFactorNumeric;
    private CurrencyAmount amount;
    private CurrencyAmount baseAmount;
}
