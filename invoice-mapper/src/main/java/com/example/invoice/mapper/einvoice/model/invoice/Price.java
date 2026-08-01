package com.example.invoice.mapper.einvoice.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Price {
    private CurrencyAmount priceAmount;
    private PriceAllowanceCharge allowanceCharge;
    private Quantity baseQuantity;
}
