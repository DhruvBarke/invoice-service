package com.example.invoice.service.domain.model.invoice;

import lombok.*;
import java.util.ArrayList;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentParty {
    private PartyLegalEntity partyLegalEntity;
    private String industryClassificationCode;
    private SchemeID endpointId;
    private PostalAddress postalAddress;
    private Contact contact;
    @Builder.Default
    private List<PartyIdentification> partyIdentification = new ArrayList<>();
    private PartyTaxScheme partyTaxScheme;
    private PartyName partyName;
}
