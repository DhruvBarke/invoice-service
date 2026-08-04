package com.sg.domaininterface.model.invoice;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class OrderReference {
    private String id;
    private String salesOrderId;
}
