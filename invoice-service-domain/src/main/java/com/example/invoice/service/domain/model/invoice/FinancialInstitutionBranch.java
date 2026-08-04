package com.example.invoice.service.domain.model.invoice;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class FinancialInstitutionBranch {
    private String id;
    private FinancialInstitution financialInstitution;
}
