package com.example.invoice.service.domain.rule;

import com.example.invoice.service.domain.model.PartyRegistrationDetails;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Chooses the single record to use when a key resolves to several.
 *
 * <p><b>A domain rule, previously misplaced in the cache.</b> Which record wins is a decision about
 * what invoice registration should use, not about caching. It sat in
 * {@code InboundPartyRegistrationCache} as a private comparator, where it was invisible to anyone
 * reviewing business behaviour and untestable without constructing a cache.
 *
 * <p><b>Determinism is the requirement, not correctness.</b> When duplicates disagree there is no
 * knowably right answer — that is what the {@code MULTIPLE_REGISTRATIONS} anomaly reports. What the
 * rule must guarantee is that repeated calls never flip between rows, because an invoice registered
 * against one and reconciled against another is a far worse failure than picking the less
 * appropriate of two.
 *
 * <p>Order: the golden record if one is present, then the lowest {@code elemBdrId} as a stable
 * tiebreak.
 */
public final class GoldenRecordSelector {

    private static final Comparator<PartyRegistrationDetails> PREFERRED =
            Comparator.comparing(PartyRegistrationDetails::isGoldenRecord).reversed()
                    .thenComparing(d -> d.elemBdrId() == null ? "" : d.elemBdrId());

    private GoldenRecordSelector() { }

    /** @return the record to use, or empty when there are none. */
    public static Optional<PartyRegistrationDetails> select(List<PartyRegistrationDetails> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() == 1) {
            return Optional.of(candidates.get(0));   // dominant case: no scan
        }
        return candidates.stream().min(PREFERRED);
    }

    /**
     * @return true when the candidates do not agree on a golden id, i.e. upstream deduplication is
     *         itself inconsistent and the selection is a genuine guess rather than a collapse
     */
    public static boolean isAmbiguous(List<PartyRegistrationDetails> candidates) {
        if (candidates == null || candidates.size() < 2) {
            return false;
        }
        String first = candidates.get(0).goldenBdrId();
        for (PartyRegistrationDetails d : candidates) {
            if (!first.equals(d.goldenBdrId())) {
                return true;
            }
        }
        return false;
    }
}
