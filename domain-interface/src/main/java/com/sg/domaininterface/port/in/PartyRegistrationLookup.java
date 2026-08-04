package com.sg.domaininterface.port.in;

import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.model.party.RegistrationType;
import java.util.List;
import java.util.Optional;

/**
 * The single contract between party registration data and its consumers.
 *
 * <p>Implementations must be safe for concurrent use and must not block indefinitely.
 *
 * <p>Any method may raise {@link PartyRegistrationUnavailableException}. Absence of a party is not a
 * failure — that returns empty.
 */
public interface PartyRegistrationLookup {

    /** Outbound path. Elementary and golden BDR ids share one namespace. */
    Optional<PartyRegistrationDetails> findByBdrId(String bdrId);

    /** Inbound path. SIREN identifies the company and yields at most one record. */
    Optional<PartyRegistrationDetails> findBySiren(String siren);

    /**
     * Inbound path. Duplicate elementary parties may share a SIRET, so this collapses them
     * deterministically — the same call always returns the same row.
     */
    Optional<PartyRegistrationDetails> findBySiret(String siret);

    /** @return every record for the SIRET, including duplicates. */
    List<PartyRegistrationDetails> findAllBySiret(String siret);

    default Optional<PartyRegistrationDetails> find(RegistrationType type, String registrationId) {
        return switch (type) {
            case SIREN -> findBySiren(registrationId);
            case SIRET -> findBySiret(registrationId);
        };
    }

    /** @throws PartyRegistrationUnavailableException with {@code NOT_FOUND} when absent. */
    default PartyRegistrationDetails requireByBdrId(String bdrId) {
        return findByBdrId(bdrId).orElseThrow(() -> new PartyRegistrationUnavailableException(
                UnavailabilityReason.NOT_FOUND, "BDR_ID", bdrId,
                "no party registration details for bdrId=" + bdrId));
    }

    default PartyRegistrationDetails requireBySiren(String siren) {
        return findBySiren(siren).orElseThrow(() -> new PartyRegistrationUnavailableException(
                UnavailabilityReason.NOT_FOUND, "SIREN", siren,
                "no party registration details for siren=" + siren));
    }
}
