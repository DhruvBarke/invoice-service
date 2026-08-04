package com.example.invoice.service.domain.model.invoice;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class Country {
    private CodedValue identificationCode;

    public String getIdentificationCodeValue() {
        return identificationCode != null ? identificationCode.getValue() : null;
    }
}
