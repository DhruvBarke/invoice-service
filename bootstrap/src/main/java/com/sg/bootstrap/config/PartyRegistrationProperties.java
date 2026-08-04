package com.sg.bootstrap.config;

import com.sg.caching.CacheConfig;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cache sizing, per key space.
 *
 * <p>Configured separately for each because SIRET is usually the highest-volume key and BDR ids are
 * usually the most stable, so one TTL and one ceiling for all three would be wrong for at least two
 * of them.
 */
@ConfigurationProperties(prefix = "invoice.service")
public class PartyRegistrationProperties {

    private final KeySpaceProperties siren = new KeySpaceProperties();
    private final KeySpaceProperties siret = new KeySpaceProperties();
    private final KeySpaceProperties bdrId = new KeySpaceProperties();

    public KeySpaceProperties getSiren() {
        return siren;
    }

    public KeySpaceProperties getSiret() {
        return siret;
    }

    public KeySpaceProperties getBdrId() {
        return bdrId;
    }

    /** See {@link CacheConfig} for what each setting does and how to choose it. */
    public static class KeySpaceProperties {
        private Duration ttl = Duration.ofMinutes(30);
        private Duration volatileTtl = Duration.ofMinutes(5);
        private double ttlJitter = 0.10;
        private double refreshAheadThreshold = 0.80;
        private int maxConcurrentRefreshes = 8;
        private Duration negativeTtl = Duration.ofMinutes(1);
        private int maxEntries = 100_000;
        private int sweepEveryInserts = 1_000;
        private int stringPoolMaxEntries = 50_000;

        public CacheConfig toCacheConfig() {
            return new CacheConfig(ttl, volatileTtl, ttlJitter, refreshAheadThreshold,
                    maxConcurrentRefreshes, negativeTtl, maxEntries, sweepEveryInserts,
                    stringPoolMaxEntries);
        }

        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }
        public Duration getVolatileTtl() { return volatileTtl; }
        public void setVolatileTtl(Duration v) { this.volatileTtl = v; }
        public double getTtlJitter() { return ttlJitter; }
        public void setTtlJitter(double v) { this.ttlJitter = v; }
        public double getRefreshAheadThreshold() { return refreshAheadThreshold; }
        public void setRefreshAheadThreshold(double v) { this.refreshAheadThreshold = v; }
        public int getMaxConcurrentRefreshes() { return maxConcurrentRefreshes; }
        public void setMaxConcurrentRefreshes(int v) { this.maxConcurrentRefreshes = v; }
        public Duration getNegativeTtl() { return negativeTtl; }
        public void setNegativeTtl(Duration v) { this.negativeTtl = v; }
        public int getMaxEntries() { return maxEntries; }
        public void setMaxEntries(int v) { this.maxEntries = v; }
        public int getSweepEveryInserts() { return sweepEveryInserts; }
        public void setSweepEveryInserts(int v) { this.sweepEveryInserts = v; }
        public int getStringPoolMaxEntries() { return stringPoolMaxEntries; }
        public void setStringPoolMaxEntries(int v) { this.stringPoolMaxEntries = v; }
    }
}
