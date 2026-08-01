package com.example.invoice.mapper.einvoice.model.invoice;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class FinancialInstitutionBranch {
    private String id;
    private FinancialInstitution financialInstitution;
}
