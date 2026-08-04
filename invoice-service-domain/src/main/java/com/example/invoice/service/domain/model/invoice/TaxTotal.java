package com.example.invoice.service.domain.model.invoice;

import lombok.*;
import java.util.ArrayList;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TaxTotal {
    private CurrencyAmount taxAmount;
    @Builder.Default
    private List<TaxSubtotal> taxSubtotal = new ArrayList<>();
}
