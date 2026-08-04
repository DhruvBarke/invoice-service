package com.sg.caching;

import java.time.Duration;

/**
 * Lifetime and sizing for one key space. Configured per key space so SIRET, usually the
 * highest-volume, can be sized differently from SIREN without either affecting the other.
 *
 * @param ttl                    normal entry lifetime
 * @param volatileTtl            lifetime of an entry the guard flagged as superseded-at-any-moment —
 *                               a correction, or a blocked key. Much shorter than {@code ttl}: these
 *                               must react to an out-of-band change rather than sitting for a full
 *                               cache lifetime.
 * @param ttlJitter              fraction (0–0.5) spreading each entry's lifetime. Without it, a
 *                               batch loaded together expires in one instant and stampedes the
 *                               referential.
 * @param refreshAheadThreshold  fraction of the lifetime after which a hit is still served but a
 *                               background refresh is dispatched, so no request pays referential
 *                               latency at the expiry boundary. 0 disables.
 *                               <p>Trades staleness for latency: at 0.80 an entry can be served up
 *                               to 20% of its lifetime past the refresh point. If that staleness is
 *                               a correctness problem rather than a cosmetic one, set 0 and accept
 *                               the boundary latency instead.
 * @param maxConcurrentRefreshes ceiling on background refreshes, so refresh-ahead cannot itself
 *                               become the load spike it exists to prevent
 * @param negativeTtl            lifetime of a cached "not found". Short enough that a newly created
 *                               party appears quickly, non-zero so unknown ids cannot loop against
 *                               the referential.
 * @param maxEntries             soft ceiling; 0 disables sweeping
 * @param sweepEveryInserts      inserts between sweep eligibility checks, so the cost is amortized
 *                               and stays off the request path
 * @param stringPoolMaxEntries   ceiling on the dedup pool, in distinct strings; 0 disables interning
 */
public record CacheConfig(
        Duration ttl,
        Duration volatileTtl,
        double ttlJitter,
        double refreshAheadThreshold,
        int maxConcurrentRefreshes,
        Duration negativeTtl,
        int maxEntries,
        int sweepEveryInserts,
        int stringPoolMaxEntries
) {
    public CacheConfig {
        requirePositive(ttl, "ttl");
        requirePositive(volatileTtl, "volatileTtl");
        requirePositive(negativeTtl, "negativeTtl");
        if (ttlJitter < 0 || ttlJitter > 0.5) {
            throw new IllegalArgumentException("ttlJitter must be within [0, 0.5]");
        }
        if (refreshAheadThreshold < 0 || refreshAheadThreshold >= 1) {
            throw new IllegalArgumentException("refreshAheadThreshold must be within [0, 1)");
        }
        if (maxConcurrentRefreshes <= 0) {
            throw new IllegalArgumentException("maxConcurrentRefreshes must be positive");
        }
        if (maxEntries < 0 || stringPoolMaxEntries < 0) {
            throw new IllegalArgumentException("ceilings must not be negative");
        }
        if (sweepEveryInserts <= 0) {
            throw new IllegalArgumentException("sweepEveryInserts must be positive");
        }
    }

    public static CacheConfig defaults() {
        return new CacheConfig(Duration.ofMinutes(30), Duration.ofMinutes(5), 0.10, 0.80, 8,
                Duration.ofMinutes(1), 100_000, 1_000, 50_000);
    }

    public boolean refreshAheadEnabled() {
        return refreshAheadThreshold > 0;
    }

    public CacheConfig withTtl(Duration v) {
        return new CacheConfig(v, volatileTtl, ttlJitter, refreshAheadThreshold,
                maxConcurrentRefreshes, negativeTtl, maxEntries, sweepEveryInserts,
                stringPoolMaxEntries);
    }

    public CacheConfig withMaxEntries(int v) {
        return new CacheConfig(ttl, volatileTtl, ttlJitter, refreshAheadThreshold,
                maxConcurrentRefreshes, negativeTtl, v, sweepEveryInserts, stringPoolMaxEntries);
    }

    private static void requirePositive(Duration d, String name) {
        if (d == null || d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
