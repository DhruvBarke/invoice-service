package com.sg.domaininterface.model.invoice;

import lombok.*;
import java.time.LocalDate;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Delivery {
    private LocalDate actualDeliveryDate;
    private DeliveryLocation deliveryLocation;
    private DeliveryParty deliveryParty;
}
