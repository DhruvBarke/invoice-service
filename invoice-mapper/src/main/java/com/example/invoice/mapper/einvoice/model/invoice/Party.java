package com.example.invoice.mapper.einvoice.model.invoice;

import lombok.*;
import java.util.ArrayList;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Party {
    private SchemeID endpointId;
    @Builder.Default
    private List<PartyIdentification> partyIdentification = new ArrayList<>();
    private PartyName partyName;
    private PostalAddress postalAddress;
    @Builder.Default
    private List<PartyTaxScheme> partyTaxScheme = new ArrayList<>();
    private PartyLegalEntity partyLegalEntity;
    private Contact contact;
    private Person person;
    private AgentParty agentParty;
    private ServiceProviderParty serviceProviderParty;

    // Convenience accessors
    public String getRegistrationName() {
        return partyLegalEntity != null ? partyLegalEntity.getRegistrationName() : null;
    }
    public String getCompanyId() {
        if (partyLegalEntity == null || partyLegalEntity.getCompanyId() == null) return null;
        return partyLegalEntity.getCompanyId().getValue();
    }
    public String getVatIdentifier() {
        if (partyTaxScheme == null || partyTaxScheme.isEmpty()) return null;
        SchemeID cid = partyTaxScheme.get(0).getCompanyId();
        return cid != null ? cid.getValue() : null;
    }
    public String getEndpointValue() {
        return endpointId != null ? endpointId.getValue() : null;
    }
    public String getEndpointScheme() {
        return endpointId != null ? endpointId.getSchemeID() : null;
    }
    public PostalAddress getPostalAddr() { return postalAddress; }
}
