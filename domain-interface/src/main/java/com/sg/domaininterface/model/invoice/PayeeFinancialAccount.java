package com.sg.domaininterface.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PayeeFinancialAccount {
    private String id;
    private String name;
    private FinancialInstitutionBranch financialInstitutionBranch;
}
