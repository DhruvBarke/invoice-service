package com.example.invoice.service.domain.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TaxSubtotal {
    private CurrencyAmount taxableAmount;
    private CurrencyAmount taxAmount;
    private TaxCategory taxCategory;
}
