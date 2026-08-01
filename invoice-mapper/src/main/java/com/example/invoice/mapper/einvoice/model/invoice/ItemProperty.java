package com.example.invoice.mapper.einvoice.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ItemProperty {
    private String name;
    private String value;
}
