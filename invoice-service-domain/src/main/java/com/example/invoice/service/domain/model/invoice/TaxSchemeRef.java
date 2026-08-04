package com.example.invoice.service.domain.model.invoice;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class TaxSchemeRef {
    private SchemeID id;

    public String getIdValue() {
        return id != null ? id.getValue() : null;
    }
}
