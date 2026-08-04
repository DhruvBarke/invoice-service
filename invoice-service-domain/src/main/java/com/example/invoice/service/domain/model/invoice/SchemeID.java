package com.example.invoice.service.domain.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SchemeID {
    private String schemeID;
    private String schemeAgencyID;
    private String value;

    public static SchemeID fromString(String val) {
        return new SchemeID(null, null, val);
    }
}
