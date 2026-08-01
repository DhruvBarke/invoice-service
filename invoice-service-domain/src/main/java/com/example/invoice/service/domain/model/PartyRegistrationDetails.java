package com.example.invoice.service.domain.model;

import java.util.List;

/**
 * Immutable registration details for one party.
 *
 * <p><b>Field obligations differ by field, and the difference matters.</b> {@code goldenBdrId} is
 * required structurally — it is the identity invoice registration uses, and a record without one
 * cannot be keyed, quarantined or corrected, so it is rejected here. {@code siren} is required by
 * <em>business</em> rule but is deliberately NOT enforced in this constructor: doing so would make
 * an offending referential row impossible to construct, log, quarantine or correct, which is
 * precisely the workflow that exists to handle it. Validation belongs to {@code AnomalyDetector}.
 * {@code siret} is genuinely optional; on the inbound path it comes from the elementary party and is
 * sometimes legitimately absent.
 */
public record PartyRegistrationDetails(
        String elemBdrId,
        String elemName,
        String elemMnemonic,
        String thirdPartyBdrId,
        String thirdPartyBdrName,
        String thirdPartyMnemonic,
        String goldenBdrId,
        String name,
        String mnemonic,
        String siren,
        String siret,
        List<Address> addresses
) {
    public PartyRegistrationDetails {
        if (goldenBdrId == null || goldenBdrId.isBlank()) {
            throw new IllegalArgumentException("goldenBdrId is required: it identifies the party");
        }
        // A record with a List component is NOT immutable unless the list is copied here.
        addresses = addresses == null ? List.of() : List.copyOf(addresses);
    }

    /** @return true when this record is the master of its duplicate set, or has no duplicates. */
    public boolean isGoldenRecord() {
        return elemBdrId == null || elemBdrId.isBlank() || goldenBdrId.equals(elemBdrId);
    }

    public boolean hasSiren() {
        return siren != null && !siren.isBlank();
    }

    public boolean hasSiret() {
        return siret != null && !siret.isBlank();
    }
}
