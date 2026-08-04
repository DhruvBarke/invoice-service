package com.sg.domain.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domaininterface.model.party.Address;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** {@link CacheConfig}, {@link CacheStats} and {@link StringPool} in isolation. */
class CacheSupportTypesTest {

  private static PartyRegistrationDetails party(String siren, List<Address> addresses) {
    return new PartyRegistrationDetails("E1", "elemName", "EMN", "TP1", "tpName", "TPM",
        "G1", "Acme SA", "ACME", siren, "12345678900012", addresses);
  }

  // ── CacheConfig ───────────────────────────────────────────────────────────

  @Nested
  @DisplayName("CacheConfig")
  class Config {

    @Test
    @DisplayName("the defaults are internally consistent")
    void defaultsAreValid() {
      CacheConfig c = CacheConfig.defaults();
      assertEquals(Duration.ofMinutes(30), c.ttl());
      assertEquals(Duration.ofMinutes(5), c.volatileTtl());
      assertTrue(c.refreshAheadEnabled());
      assertTrue(c.volatileTtl().compareTo(c.ttl()) < 0,
          "a superseded entry must expire sooner than a healthy one");
    }

    @Test
    @DisplayName("refresh-ahead is disabled by a zero threshold")
    void refreshAheadToggle() {
      assertFalse(new CacheConfig(Duration.ofMinutes(30), Duration.ofMinutes(5), 0.1, 0.0, 8,
          Duration.ofMinutes(1), 100, 10, 100).refreshAheadEnabled());
    }

    @Test
    @DisplayName("withTtl and withMaxEntries change one field and keep the rest")
    void withersPreserveEverythingElse() {
      CacheConfig base = CacheConfig.defaults();

      CacheConfig ttl = base.withTtl(Duration.ofHours(2));
      assertEquals(Duration.ofHours(2), ttl.ttl());
      assertEquals(base.maxEntries(), ttl.maxEntries());
      assertEquals(base.volatileTtl(), ttl.volatileTtl());

      CacheConfig max = base.withMaxEntries(7);
      assertEquals(7, max.maxEntries());
      assertEquals(base.ttl(), max.ttl());
    }

    @Test
    @DisplayName("every duration must be positive")
    void durationsMustBePositive() {
      assertThrows(IllegalArgumentException.class, () -> new CacheConfig(
          null, Duration.ofMinutes(5), 0.1, 0.8, 8, Duration.ofMinutes(1), 100, 10, 100));
      assertThrows(IllegalArgumentException.class, () -> new CacheConfig(
          Duration.ZERO, Duration.ofMinutes(5), 0.1, 0.8, 8, Duration.ofMinutes(1), 100, 10, 100));
      assertThrows(IllegalArgumentException.class, () -> new CacheConfig(
          Duration.ofMinutes(-1), Duration.ofMinutes(5), 0.1, 0.8, 8,
          Duration.ofMinutes(1), 100, 10, 100));
      assertThrows(IllegalArgumentException.class, () -> new CacheConfig(
          Duration.ofMinutes(30), null, 0.1, 0.8, 8, Duration.ofMinutes(1), 100, 10, 100));
      assertThrows(IllegalArgumentException.class, () -> new CacheConfig(
          Duration.ofMinutes(30), Duration.ofMinutes(5), 0.1, 0.8, 8, null, 100, 10, 100));
    }

    @Test
    @DisplayName("jitter is capped at half the lifetime")
    void jitterIsBounded() {
      assertThrows(IllegalArgumentException.class, () -> new CacheConfig(
          Duration.ofMinutes(30), Duration.ofMinutes(5), -0.1, 0.8, 8,
          Duration.ofMinutes(1), 100, 10, 100));
      assertThrows(IllegalArgumentException.class, () -> new CacheConfig(
          Duration.ofMinutes(30), Duration.ofMinutes(5), 0.6, 0.8, 8,
          Duration.ofMinutes(1), 100, 10, 100));
      assertNotNull(new CacheConfig(Duration.ofMinutes(30), Duration.ofMinutes(5), 0.5, 0.8, 8,
          Duration.ofMinutes(1), 100, 10, 100), "0.5 is the inclusive upper bound");
    }

    @Test
    @DisplayName("the refresh threshold must be a fraction below 1")
    void refreshThresholdIsBounded() {
      assertThrows(IllegalArgumentException.class, () -> new CacheConfig(
          Duration.ofMinutes(30), Duration.ofMinutes(5), 0.1, -0.1, 8,
          Duration.ofMinutes(1), 100, 10, 100));
      assertThrows(IllegalArgumentException.class, () -> new CacheConfig(
          Duration.ofMinutes(30), Duration.ofMinutes(5), 0.1, 1.0, 8,
          Duration.ofMinutes(1), 100, 10, 100),
          "a threshold of 1 would mean refreshing exactly at expiry, which is just a miss");
    }

    @Test
    @DisplayName("the concurrency ceiling and sweep interval must be positive")
    void countersMustBePositive() {
      assertThrows(IllegalArgumentException.class, () -> new CacheConfig(
          Duration.ofMinutes(30), Duration.ofMinutes(5), 0.1, 0.8, 0,
          Duration.ofMinutes(1), 100, 10, 100));
      assertThrows(IllegalArgumentException.class, () -> new CacheConfig(
          Duration.ofMinutes(30), Duration.ofMinutes(5), 0.1, 0.8, 8,
          Duration.ofMinutes(1), 100, 0, 100));
    }

    @Test
    @DisplayName("the ceilings may be zero (disabled) but never negative")
    void ceilingsMayBeZero() {
      assertNotNull(new CacheConfig(Duration.ofMinutes(30), Duration.ofMinutes(5), 0.1, 0.8, 8,
          Duration.ofMinutes(1), 0, 10, 0), "zero disables sweeping and interning");
      assertThrows(IllegalArgumentException.class, () -> new CacheConfig(
          Duration.ofMinutes(30), Duration.ofMinutes(5), 0.1, 0.8, 8,
          Duration.ofMinutes(1), -1, 10, 100));
      assertThrows(IllegalArgumentException.class, () -> new CacheConfig(
          Duration.ofMinutes(30), Duration.ofMinutes(5), 0.1, 0.8, 8,
          Duration.ofMinutes(1), 100, 10, -1));
    }
  }

  // ── CacheStats ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("CacheStats")
  class Stats {

    private CacheStats stats(String keySpace, long hits, long misses, int entries) {
      return new CacheStats(keySpace, hits, misses, 0, 0, 0, 3, 0, 1, 0, 0, entries, 5, 9);
    }

    @Test
    @DisplayName("the hit ratio is hits over total")
    void hitRatio() {
      assertEquals(0.75, stats("SIREN", 3, 1, 4).hitRatio(), 1e-9);
    }

    @Test
    @DisplayName("an untouched cache reports zero rather than dividing by zero")
    void hitRatioOnEmptyCache() {
      assertEquals(0.0, stats("SIREN", 0, 0, 0).hitRatio(), 1e-9);
    }

    @Test
    @DisplayName("the summary reports each key space on its own line")
    void summarizeReportsPerKeySpace() {
      String out = CacheStats.summarize(List.of(stats("SIREN", 3, 1, 4), stats("SIRET", 1, 1, 2)));
      assertTrue(out.contains("SIREN: entries=4"));
      assertTrue(out.contains("SIRET: entries=2"));
      assertTrue(out.contains("hitRatio=0.750"));
      assertEquals(2, out.lines().count(),
          "one line per key space — a problem in one flow must not be averaged away");
    }

    @Test
    @DisplayName("an empty list summarises to an empty string")
    void summarizeEmpty() {
      assertEquals("", CacheStats.summarize(List.of()));
    }
  }

  // ── StringPool ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("StringPool")
  class Pool {

    @Test
    @DisplayName("a repeated value returns the very first instance")
    void repeatedValueIsDeduplicated() {
      StringPool pool = new StringPool(100);
      String first = new String("123456789");
      String second = new String("123456789");

      assertSame(first, pool.canonicalize(first));
      assertSame(first, pool.canonicalize(second),
          "the second caller gets the instance the first one interned");
      assertEquals(1, pool.hitCount());
      assertEquals(1, pool.size());
    }

    @Test
    @DisplayName("null passes straight through")
    void nullPassesThrough() {
      assertNull(new StringPool(100).canonicalize((String) null));
    }

    @Test
    @DisplayName("a long value is not worth a map entry")
    void longValuesAreNotPooled() {
      StringPool pool = new StringPool(100);
      String longValue = "x".repeat(65);
      assertSame(longValue, pool.canonicalize(longValue));
      assertEquals(0, pool.size(), "a full address line would dedupe nothing");
    }

    @Test
    @DisplayName("a zero ceiling disables interning entirely")
    void zeroCeilingDisablesPooling() {
      StringPool pool = new StringPool(0);
      String value = new String("123456789");
      assertSame(value, pool.canonicalize(value));
      assertEquals(0, pool.size());
    }

    @Test
    @DisplayName("a full pool degrades to no interning rather than growing")
    void fullPoolDegradesGracefully() {
      StringPool pool = new StringPool(1);
      pool.canonicalize("first");
      String overflow = new String("second");
      assertSame(overflow, pool.canonicalize(overflow),
          "interning is an optimisation — running out must never be an error");
      assertEquals(1, pool.size());
    }

    @Test
    @DisplayName("a record is rebuilt with every field routed through the pool")
    void recordFieldsArePooled() {
      StringPool pool = new StringPool(100);
      pool.canonicalize("123456789");

      PartyRegistrationDetails rebuilt = pool.canonicalize(party(new String("123456789"), List.of()));
      assertEquals("123456789", rebuilt.siren());
      assertTrue(pool.hitCount() > 0, "the repeated siren should have hit the pool");
    }

    @Test
    @DisplayName("a null record stays null")
    void nullRecordStaysNull() {
      assertNull(new StringPool(100).canonicalize((PartyRegistrationDetails) null));
    }

    @Test
    @DisplayName("an empty address list becomes the shared singleton")
    void emptyAddressesUseTheSingleton() {
      PartyRegistrationDetails rebuilt =
          new StringPool(100).canonicalize(party("123456789", List.of()));
      assertTrue(rebuilt.addresses().isEmpty());
    }

    @Test
    @DisplayName("address fields are pooled and the flag is preserved")
    void addressesArePooled() {
      StringPool pool = new StringPool(100);
      Address a = new Address("HQ", "1 rue", "etage 2", "75009", "PARIS", "FR", true);
      Address b = new Address("HQ", "2 rue", null, "75009", "PARIS", "FR", false);

      PartyRegistrationDetails rebuilt =
          pool.canonicalize(party("123456789", List.of(a, b)));

      assertEquals(2, rebuilt.addresses().size());
      assertTrue(rebuilt.addresses().get(0).primary());
      assertFalse(rebuilt.addresses().get(1).primary());
      assertEquals("PARIS", rebuilt.addresses().get(1).city());
      assertNull(rebuilt.addresses().get(1).line2());
      assertSame(rebuilt.addresses().get(0).city(), rebuilt.addresses().get(1).city(),
          "the repeated city should be one instance across both addresses");
    }

    @Test
    @DisplayName("two threads interning the same new value converge on one instance")
    void concurrentFirstInsertConvergesOnOneInstance() throws Exception {
      // canonicalize does get-then-putIfAbsent. When two threads miss the get for the same new
      // value, one putIfAbsent wins and the other is handed the winner's instance — the arm
      // that makes the pool actually deduplicate under contention rather than handing each
      // caller its own copy. A barrier per round makes the collision reliable.
      StringPool pool = new StringPool(100_000);
      int rounds = 500;
      CyclicBarrier barrier = new CyclicBarrier(2);
      List<String> mismatches = Collections.synchronizedList(new ArrayList<>());

      ExecutorService pair = Executors.newFixedThreadPool(2);
      try {
        for (int i = 0; i < rounds; i++) {
          String a = new String("VALUE_" + i);
          String b = new String("VALUE_" + i);

          Future<String> left = pair.submit(() -> {
            barrier.await(5, TimeUnit.SECONDS);
            return pool.canonicalize(a);
          });
          Future<String> right = pair.submit(() -> {
            barrier.await(5, TimeUnit.SECONDS);
            return pool.canonicalize(b);
          });

          String l = left.get(5, TimeUnit.SECONDS);
          String r = right.get(5, TimeUnit.SECONDS);
          if (l != r) {
            mismatches.add("VALUE_" + i);
          }
        }
      } finally {
        pair.shutdownNow();
      }

      assertTrue(mismatches.isEmpty(),
          "both threads must end up holding the same instance, else the pool is not "
              + "deduplicating under the race it exists to handle; mismatched: " + mismatches);
      assertEquals(rounds, pool.size(), "one entry per distinct value, never two");
    }

    @Test
    @DisplayName("clear empties the pool without disturbing records already holding instances")
    void clearIsAlwaysSafe() {
      StringPool pool = new StringPool(100);
      String pooled = pool.canonicalize(new String("123456789"));
      pool.clear();
      assertEquals(0, pool.size());
      assertEquals("123456789", pooled, "a record that already holds the instance keeps working");
    }
  }
}
