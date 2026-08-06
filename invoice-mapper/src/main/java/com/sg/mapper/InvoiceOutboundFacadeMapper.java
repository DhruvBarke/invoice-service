package com.sg.mapper;

import com.sg.domaininterface.port.out.PartyRegistrationLookup;
import java.util.Objects;

/**
 * Maps an outbound invoice's BDR identifier to invoice party details.
 *
 * <p>An outbound lookup may legitimately carry an elementary id, so the returned record's
 * {@code elemBdrId} and {@code goldenBdrId} can differ. Registration still uses the golden id.
 */
public final class InvoiceOutboundFacadeMapper {

    private final PartyRegistrationLookup lookup;

    public InvoiceOutboundFacadeMapper(PartyRegistrationLookup lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    public InvoiceParty mapCounterparty(String bdrId) {
        return InvoiceInboundFacadeMapper.toInvoiceParty(lookup.requireByBdrId(bdrId));
    }
}
