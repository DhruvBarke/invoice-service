package com.sg.caching;

import com.sg.domaininterface.model.party.Address;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Deduplicates repeated strings inside cached records. Roughly a quarter off the retained size of a
 * typical entry, at zero read cost.
 *
 * <p><b>Not {@link String#intern()}</b>, whose pool lives in native memory, is effectively unbounded,
 * and is reclaimed only at GC's discretion. This one is a plain bounded map on the Java heap: visible
 * in a heap dump, sized by configuration, and clearable.
 *
 * <p><b>Always safe to abandon.</b> Interning is an optimization, never a correctness requirement, so
 * a full pool simply stops interning and {@link #clear()} can be called at any time. Records already
 * holding pooled instances keep working.
 */
final class StringPool {

    /**
     * Above this length a value is almost certainly a one-off — a full name or address line rather
     * than an id, code or registration number — so pooling it would cost a map entry to dedupe
     * nothing.
     */
    private static final int MAX_POOLED_LENGTH = 64;

    private final ConcurrentHashMap<String, String> pool = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final LongAdder hits = new LongAdder();

    StringPool(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    String canonicalize(String value) {
        if (value == null || value.length() > MAX_POOLED_LENGTH || maxEntries <= 0) {
            return value;
        }
        String existing = pool.get(value);
        if (existing != null) {
            hits.increment();
            return existing;
        }
        if (pool.size() >= maxEntries) {
            return value;   // full: degrade to no interning rather than growing without bound
        }
        existing = pool.putIfAbsent(value, value);
        return existing != null ? existing : value;
    }

    /**
     * Rebuilds a record with every field routed through the pool.
     *
     * <p>Allocates one replacement; the gateway's original becomes garbage immediately, so the net
     * effect on a young collection is nil while the retained form is materially smaller.
     */
    PartyRegistrationDetails canonicalize(PartyRegistrationDetails d) {
        if (d == null) {
            return null;
        }
        return new PartyRegistrationDetails(
                canonicalize(d.elemBdrId()), canonicalize(d.elemName()), canonicalize(d.elemMnemonic()),
                canonicalize(d.thirdPartyBdrId()), canonicalize(d.thirdPartyBdrName()),
                canonicalize(d.thirdPartyMnemonic()), canonicalize(d.goldenBdrId()),
                canonicalize(d.name()), canonicalize(d.mnemonic()),
                canonicalize(d.siren()), canonicalize(d.siret()),
                canonicalizeAddresses(d.addresses()));
    }

    private List<Address> canonicalizeAddresses(List<Address> addresses) {
        if (addresses.isEmpty()) {
            return List.of();   // the shared empty singleton
        }
        List<Address> out = new ArrayList<>(addresses.size());
        for (Address a : addresses) {
            out.add(new Address(canonicalize(a.usage()), canonicalize(a.line1()),
                    canonicalize(a.line2()), canonicalize(a.postalCode()), canonicalize(a.city()),
                    canonicalize(a.countryCode()), a.primary()));
        }
        return List.copyOf(out);
    }

    int size() {
        return pool.size();
    }

    long hitCount() {
        return hits.sum();
    }

    void clear() {
        pool.clear();
    }
}
