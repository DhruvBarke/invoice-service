package com.example.invoice.service.alerting.quarantine;

import com.example.invoice.service.domain.model.PartyRegistrationDetails;
import java.util.List;

/**
 * Serializes payloads for the quarantine table.
 *
 * <p>An interface rather than a fixed choice, so the project's existing JSON library is used and no
 * new dependency is imposed on every consumer.
 */
public interface RecordCodec {

    /** @return the serialized form, or {@code null} when {@code records} is null. */
    String serialize(List<PartyRegistrationDetails> records);

    /** @return the deserialized records, or {@code null} when {@code payload} is null or blank. */
    List<PartyRegistrationDetails> deserialize(String payload);
}
