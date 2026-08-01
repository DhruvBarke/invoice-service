package com.example.invoice.mapper.einvoice.model.invoice;

import lombok.*;

/** EXT-FR-FE-BG-14: Delivery terms / Incoterms (EXTENDED profile only). */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DeliveryTerms {
    private String id;
    private String specialTerms;
    private DeliveryLocation deliveryLocation;
}
