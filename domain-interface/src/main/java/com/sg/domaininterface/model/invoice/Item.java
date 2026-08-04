package com.sg.domaininterface.model.invoice;

import lombok.*;
import java.util.ArrayList;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Item {
    private String name;
    private String description;
    private SimpleItemId sellersItemIdentification;
    private SimpleItemId buyersItemIdentification;
    private StandardItemId standardItemIdentification;
    private CommodityClassification commodityClassification;
    @Builder.Default
    private List<ItemProperty> additionalItemProperty = new ArrayList<>();
    @Builder.Default
    private List<TaxCategory> classifiedTaxCategory = new ArrayList<>();
    private OriginCountryRef originCountry;
    private AgentParty manufacturerParty;
}
