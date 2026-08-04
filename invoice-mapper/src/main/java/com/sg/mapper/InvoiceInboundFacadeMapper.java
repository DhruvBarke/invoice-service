package com.sg.mapper;

import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.port.out.PartyRegistrationLookup;
import java.util.Objects;
import java.util.Optional;

/**
 * Maps an inbound invoice's supplier identifiers to invoice party details.
 *
 * <p>Inbound lookups are almost always by SIREN; the SIRET comes back from the elementary party and
 * is sometimes legitimately absent, so callers must tolerate a null SIRET.
 */
public final class InvoiceInboundFacadeMapper {

    private final PartyRegistrationLookup lookup;

    public InvoiceInboundFacadeMapper(PartyRegistrationLookup lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    /**
     * @throws PartyRegistrationUnavailableException when the party is unknown, or its details are
     *         blocked pending a correction. Inspect {@code isRetryable()} to decide between a retry
     *         and routing to an operator, and surface {@code referenceId()} when present.
     */
    public InvoiceParty mapSupplierBySiren(String siren) {
        return toInvoiceParty(lookup.requireBySiren(siren));
    }

    public Optional<InvoiceParty> mapSupplierBySiret(String siret) {
        return lookup.findBySiret(siret).map(InvoiceInboundFacadeMapper::toInvoiceParty);
    }

    /** Registration always keys on the golden id — never {@code elemBdrId}, even when they differ. */
    static InvoiceParty toInvoiceParty(PartyRegistrationDetails d) {
        return new InvoiceParty(d.goldenBdrId(), d.name(), d.mnemonic(), d.siren(), d.siret());
    }
}
