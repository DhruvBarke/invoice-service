package com.sg.domaininterface.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class LegalMonetaryTotal {
    private CurrencyAmount lineExtensionAmount;
    private CurrencyAmount taxExclusiveAmount;
    private CurrencyAmount taxInclusiveAmount;
    private CurrencyAmount allowanceTotalAmount;
    private CurrencyAmount chargeTotalAmount;
    private CurrencyAmount prepaidAmount;
    private CurrencyAmount payableRoundingAmount;
    private CurrencyAmount payableAmount;
}
