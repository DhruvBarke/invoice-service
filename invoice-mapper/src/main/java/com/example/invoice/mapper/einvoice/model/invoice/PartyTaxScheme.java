package com.example.invoice.mapper.einvoice.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PartyTaxScheme {
    private SchemeID companyId;
    private TaxSchemeRef taxScheme;
}
