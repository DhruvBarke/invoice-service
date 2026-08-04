package com.sg.domaininterface.model.invoice;

import lombok.*;
import java.util.ArrayList;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CommodityClassification {
    @Builder.Default
    private List<ItemClassificationCode> itemClassificationCode = new ArrayList<>();
}
