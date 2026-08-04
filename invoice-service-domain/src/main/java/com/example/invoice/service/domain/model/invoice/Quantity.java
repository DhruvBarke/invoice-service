package com.example.invoice.service.domain.model.invoice;

import lombok.*;
import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Quantity {
    private String unitCode;
    private BigDecimal value;
}
