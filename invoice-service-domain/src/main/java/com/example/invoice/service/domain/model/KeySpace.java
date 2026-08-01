package com.example.invoice.service.domain.model;

import java.util.Locale;

/**
 * An identifier namespace a party can be looked up in.
 *
 * <p>Three namespaces, not four: elementary and golden BDR ids share {@link #BDR_ID}, because the
 * referential's request slot is always interpreted as an elementary id and a golden id is valid
 * there only by virtue of the golden record being itself an elementary party. Splitting them would
 * mean two indices over one identifier space, with two expiry clocks that can disagree.
 *
 * <p>Each namespace owns its normalization and its cardinality, so downstream code never has to ask
 * "is this the multi-valued one" — it asks the key space.
 *
 * <p><b>On the mutual reference with {@link RegistrationType}.</b> The two enums refer to each other,
 * which would deadlock if either touched the other during construction. Neither does: this enum's
 * constructor takes only a boolean, and the reference to {@code RegistrationType} appears solely
 * inside {@link #normalize}, which runs long after both classes are initialized. Keep it that way —
 * moving a {@code RegistrationType} reference into a constructor here would produce a null constant
 * or a class-initialization deadlock depending on which class loads first.
 */
public enum KeySpace {

    /** Elementary or golden BDR identifier. Opaque; resolves to at most one record. */
    BDR_ID(false) {
        @Override
        public String normalize(String raw) {
            if (raw == null) {
                return null;
            }
            // Both strip() and toUpperCase return the receiver when there is nothing to change, so
            // clean ids allocate nothing. Locale.ROOT avoids the Turkish dotted-I mapping of the
            // default locale.
            String normalized = raw.strip().toUpperCase(Locale.ROOT);
            return normalized.isEmpty() ? null : normalized;
        }
    },

    SIREN(false) {
        @Override
        public String normalize(String raw) {
            return RegistrationType.SIREN.normalize(raw);
        }
    },

    /** The only namespace where several records for one key are legitimate. */
    SIRET(true) {
        @Override
        public String normalize(String raw) {
            return RegistrationType.SIRET.normalize(raw);
        }
    };

    private final boolean multiValued;

    KeySpace(boolean multiValued) {
        this.multiValued = multiValued;
    }

    /** @return the canonical form of a caller-supplied value, or {@code null} if unusable. */
    public abstract String normalize(String raw);

    /**
     * @return true when several records for one key are expected rather than a defect. Drives the
     *         {@code MULTIPLE_REGISTRATIONS} check.
     */
    public boolean isMultiValued() {
        return multiValued;
    }
}
