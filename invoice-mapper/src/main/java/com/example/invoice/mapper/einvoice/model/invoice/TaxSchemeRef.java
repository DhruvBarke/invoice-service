package com.example.invoice.mapper.einvoice.model.invoice;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class TaxSchemeRef {
    private SchemeID id;

    public String getIdValue() {
        return id != null ? id.getValue() : null;
    }
}
