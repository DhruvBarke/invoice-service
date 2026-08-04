package com.sg.domaininterface.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AllowanceCharge {
    private String chargeIndicator;
    private String allowanceChargeReasonCode;
    private String allowanceChargeReason;
    private String multiplierFactorNumeric;
    private CurrencyAmount amount;
    private CurrencyAmount baseAmount;
    private TaxCategory taxCategory;
}
