package com.example.invoice.service.domain.model.invoice;

import lombok.*;
import java.util.ArrayList;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ServiceProviderInnerParty {
    private PartyLegalEntity partyLegalEntity;
    private SchemeID endpointId;
    private PostalAddress postalAddress;
    private Contact contact;
    @Builder.Default
    private List<PartyIdentification> partyIdentification = new ArrayList<>();
    private PartyName partyName;
    private PartyTaxScheme partyTaxScheme;
    private String industryClassificationCode;
}
