package com.sg.domaininterface.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BillingReferenceLine {
    private String id;
    private String lineTypeCode;
}
