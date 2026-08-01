package com.example.invoice.mapper.einvoice.model.invoice;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class RegistrationAddress {
    private String cityName;
    private String countrySubentity;
    private Country country;
}
