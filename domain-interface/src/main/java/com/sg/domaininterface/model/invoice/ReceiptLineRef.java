package com.sg.domaininterface.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReceiptLineRef {
    private String lineId;
    private DocumentReference documentReference;
}
