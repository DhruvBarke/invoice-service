package com.example.invoice.mapper.einvoice.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PostalAddress {
    private SchemeID id;
    private String streetName;
    private String additionalStreetName;
    private String postbox;
    private String buildingNumber;
    private String department;
    private AddressLine addressLine;
    private String cityName;
    private String postalZone;
    private String countrySubentity;
    private String countrySubentityCode;
    private Country country;

    /** Convenience: get country code from nested structure */
    public String getCountryCode() {
        return country != null ? country.getIdentificationCodeValue() : null;
    }
}
