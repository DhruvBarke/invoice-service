package com.example.invoice.service.domain.model.invoice;

import lombok.*;
import java.util.ArrayList;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TaxRepresentativeParty {
    private PartyName partyName;
    @Builder.Default
    private List<PartyTaxScheme> partyTaxScheme = new ArrayList<>();
    private PostalAddress postalAddress;
}
