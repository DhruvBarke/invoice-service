package com.sg.caching;

import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.port.out.PartyRegistrationLookup;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The caching implementation of the driving port — the bean the invoice service registers and the
 * mappers inject.
 *
 * <p>A thin router over the two flow caches rather than a third cache: inbound and outbound stay
 * fully independent, and this type adds no state of its own.
 */
public final class CachingPartyRegistrationLookup implements PartyRegistrationLookup, AutoCloseable {

    private final InboundPartyRegistrationCache inbound;
    private final OutboundPartyRegistrationCache outbound;

    public CachingPartyRegistrationLookup(InboundPartyRegistrationCache inbound,
                                          OutboundPartyRegistrationCache outbound) {
        this.inbound = Objects.requireNonNull(inbound, "inbound");
        this.outbound = Objects.requireNonNull(outbound, "outbound");
    }

    @Override
    public Optional<PartyRegistrationDetails> findByBdrId(String bdrId) {
        return outbound.findByBdrId(bdrId);
    }

    @Override
    public Optional<PartyRegistrationDetails> findBySiren(String siren) {
        return inbound.findBySiren(siren);
    }

    @Override
    public Optional<PartyRegistrationDetails> findBySiret(String siret) {
        return inbound.findBySiret(siret);
    }

    @Override
    public List<PartyRegistrationDetails> findAllBySiret(String siret) {
        return inbound.findAllBySiret(siret);
    }

    /** Exposed for an operations endpoint; not part of the consumer-facing contract. */
    public InboundPartyRegistrationCache inbound() {
        return inbound;
    }

    public OutboundPartyRegistrationCache outbound() {
        return outbound;
    }

    @Override
    public void close() {
        inbound.close();
        outbound.close();
    }
}
