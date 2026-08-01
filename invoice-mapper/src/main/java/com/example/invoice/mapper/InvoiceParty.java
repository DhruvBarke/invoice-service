package com.example.invoice.mapper;

/**
 * The invoice module's view of a party.
 *
 * <p>A placeholder for whatever type your invoice domain already uses — replace it, and adjust the
 * mappers accordingly. The point of the mappers is that this type, not
 * {@code PartyRegistrationDetails}, is what the rest of the invoice code sees.
 *
 * @param registrationId always the golden BDR id; see the package documentation
 */
public record InvoiceParty(String registrationId, String name, String mnemonic,
                           String siren, String siret) { }
