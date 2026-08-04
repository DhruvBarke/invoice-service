package com.sg.domaininterface.model.party;

/**
 * The kind of registration number used on the inbound path.
 *
 * <p>Carries its own normalization, so every module agrees on identifier equality. See the package
 * documentation for why a single shared normalizer matters.
 */
public enum RegistrationType {

    /** Company-level, 9 digits. Resolves to at most one record. */
    SIREN(9, KeySpace.SIREN),

    /**
     * Office-level, 14 digits. The only key that may legitimately resolve to several records,
     * because duplicate elementary parties share a SIRET.
     */
    SIRET(14, KeySpace.SIRET);

    private final int digits;
    private final KeySpace keySpace;

    RegistrationType(int digits, KeySpace keySpace) {
        this.digits = digits;
        this.keySpace = keySpace;
    }

    public int digits() {
        return digits;
    }

    public KeySpace keySpace() {
        return keySpace;
    }

    /**
     * @return the canonical form, or {@code null} if the value cannot be one.
     *
     * <p>Values arrive both machine-clean ({@code "12345678900012"}) and human-formatted
     * ({@code "123 456 789 00012"}). The clean form dominates the hot path, so it is detected with a
     * scan and returned as-is — zero allocation. Only formatted input pays for a StringBuilder.
     *
     * <p>Wrong lengths are rejected here rather than at the endpoint: failing fast saves a pointless
     * round trip and keeps junk out of the key space. No Luhn checksum — La Poste's SIRETs are a
     * documented exception and would be wrongly rejected.
     */
    public String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        final int len = raw.length();

        if (len == digits) {                       // fast path: already canonical?
            boolean clean = true;
            for (int i = 0; i < len; i++) {
                char c = raw.charAt(i);
                if (c < '0' || c > '9') {
                    clean = false;
                    break;
                }
            }
            if (clean) {
                return raw;
            }
        }

        StringBuilder out = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                out.append(c);
            }
        }
        return out.length() == digits ? out.toString() : null;
    }
}
