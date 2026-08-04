package com.example.invoice.service.domain.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DeliveryLocation {
    private SchemeID id;
    private String name;
    private PostalAddress address;
}
