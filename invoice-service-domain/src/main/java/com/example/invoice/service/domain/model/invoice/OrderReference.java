package com.example.invoice.service.domain.model.invoice;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class OrderReference {
    private String id;
    private String salesOrderId;
}
