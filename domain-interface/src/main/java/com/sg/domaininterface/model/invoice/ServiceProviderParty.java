package com.sg.domaininterface.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ServiceProviderParty {
    private ServiceProviderInnerParty party;
}
