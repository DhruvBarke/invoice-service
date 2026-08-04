package com.sg.domaininterface.port.out;

import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.model.party.RegistrationType;
import java.util.List;

/**
 * Source of party registration details.
 *
 * <p>Implementations translate the referential's own types into domain records. See the package
 * documentation for why this port is narrower than the referential's interface.
 *
 * <p><b>An unknown key must return an empty list, not throw.</b> Absence and unavailability are
 * different: the first is cached negatively so an unknown id cannot hammer the endpoint, the second
 * must never be cached at all because it is transient. An adapter that conflates them will cause one
 * of those two behaviours to be wrong.
 */
public interface ReferentialGateway {

    /**
     * @param bdrId always an elementary id; a golden id is valid because the golden record is itself
     *              an elementary party
     * @throws ReferentialUnavailableException when the endpoint cannot be reached
     */
    List<PartyRegistrationDetails> searchByBdrId(String bdrId);

    /** @throws ReferentialUnavailableException when the endpoint cannot be reached */
    List<PartyRegistrationDetails> searchByRegistration(String registrationId, RegistrationType type);

    /** Signals a transient failure. Never cached, so a recovered endpoint is used immediately. */
    class ReferentialUnavailableException extends RuntimeException {
        public ReferentialUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
