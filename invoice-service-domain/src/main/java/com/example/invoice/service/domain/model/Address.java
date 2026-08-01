package com.example.invoice.service.domain.model;

import java.util.Objects;

/** A postal address attached to a party. A record, so it is deeply immutable. */
public record Address(
        String usage,          // e.g. REGISTERED_OFFICE, MAILING, TRADING
        String line1,
        String line2,
        String postalCode,
        String city,
        String countryCode,    // ISO 3166-1 alpha-2
        boolean primary
) {
    public Address {
        Objects.requireNonNull(countryCode, "countryCode");
    }
}
