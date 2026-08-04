package com.example.invoice.service.domain.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PaymentMandate {
    private String id;
    private Party payerParty;
}
