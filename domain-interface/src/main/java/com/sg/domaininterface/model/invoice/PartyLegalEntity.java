package com.sg.domaininterface.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PartyLegalEntity {
    private String registrationName;
    private SchemeID companyId;
    private String companyLegalForm;
    private TaxSchemeRef taxScheme;
    private RegistrationAddress registrationAddress;
}
