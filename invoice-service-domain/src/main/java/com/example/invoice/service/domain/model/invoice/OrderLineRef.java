package com.example.invoice.service.domain.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderLineRef {
    private String lineId;
    private String salesOrderLineId;
}
