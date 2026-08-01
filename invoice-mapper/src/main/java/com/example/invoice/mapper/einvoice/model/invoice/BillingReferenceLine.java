package com.example.invoice.mapper.einvoice.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BillingReferenceLine {
    private String id;
    private String lineTypeCode;
}
