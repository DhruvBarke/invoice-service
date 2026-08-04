package com.example.invoice.service.domain.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PartyTaxScheme {
    private SchemeID companyId;
    private TaxSchemeRef taxScheme;
}
