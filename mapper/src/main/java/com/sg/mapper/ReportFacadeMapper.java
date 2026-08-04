package com.sg.mapper;

import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.port.in.PartyRegistrationLookup;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Maps party identifiers for reporting.
 *
 * <p>Reporting is the one consumer that genuinely wants <em>all</em> records for a SIRET rather than
 * the collapsed golden one: a report over establishments should show every duplicate rather than
 * silently picking a winner.
 */
public final class ReportFacadeMapper {

    private final PartyRegistrationLookup lookup;

    public ReportFacadeMapper(PartyRegistrationLookup lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    /** @return every record sharing the SIRET, duplicates included. */
    public List<PartyRegistrationDetails> allEstablishments(String siret) {
        return lookup.findAllBySiret(siret);
    }

    /** Reports tolerate absence: an unresolvable party becomes a blank cell, not a failure. */
    public Optional<String> displayName(String bdrId) {
        return lookup.findByBdrId(bdrId).map(PartyRegistrationDetails::name);
    }
}
