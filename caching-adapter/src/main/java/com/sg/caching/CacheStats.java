package com.sg.caching;

import java.util.List;

/**
 * Counters for one key space, reported separately rather than summed so a problem confined to one
 * flow is visible instead of being averaged away by healthy traffic elsewhere.
 *
 * <p>{@code pooledStringHits} against {@code entries} is the number that tells you whether interning
 * is earning its keep. If it stays low, the access pattern is one office per company and the pool's
 * own footprint is not being repaid — set {@code stringPoolMaxEntries} to 0 and reclaim it.
 */
public record CacheStats(
        String keySpace,
        long hits,
        long misses,
        long negativeHits,
        long staleHits,
        long refreshAheads,
        long loads,
        long loadFailures,
        long guardBlocks,
        long blockedHits,
        long coalescedLoads,
        int entries,
        int pooledStrings,
        long pooledStringHits
) {
    public double hitRatio() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }

    public static String summarize(List<CacheStats> all) {
        StringBuilder sb = new StringBuilder();
        for (CacheStats s : all) {
            sb.append(s.keySpace()).append(": entries=").append(s.entries())
              .append(" hitRatio=").append(String.format("%.3f", s.hitRatio()))
              .append(" loads=").append(s.loads())
              .append(" blocks=").append(s.guardBlocks())
              .append(" pooled=").append(s.pooledStrings()).append('\n');
        }
        return sb.toString();
    }
}
