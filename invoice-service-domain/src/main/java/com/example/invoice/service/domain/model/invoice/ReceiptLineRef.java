package com.example.invoice.service.domain.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReceiptLineRef {
    private String lineId;
    private DocumentReference documentReference;
}
