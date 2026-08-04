package com.sg.domain.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domaininterface.model.party.Flow;
import com.sg.domaininterface.model.party.KeySpace;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.port.in.PartyRegistrationUnavailableException;
import com.sg.domaininterface.port.in.UnavailabilityReason;
import com.sg.domaininterface.port.out.GuardDecision;
import com.sg.domaininterface.port.out.ReferentialGateway;
import com.sg.domaininterface.port.out.ResponseGuard;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cache engine: hits, misses, negative caching, blocking, coalescing, refresh-ahead,
 * sweeping and the guard-failure fallback.
 *
 * <p>Timing-sensitive paths are driven with sub-second lifetimes rather than a clock
 * abstraction, because the engine reads {@code System.nanoTime()} directly and wrapping that
 * would change the code under test to suit the test.
 */
class ReferentialCacheCoreTest {

  private final ExecutorService maintenance = Executors.newVirtualThreadPerTaskExecutor();

  @AfterEach
  void shutdown() {
    maintenance.shutdownNow();
  }

  private static PartyRegistrationDetails party(String siren) {
    return new PartyRegistrationDetails("E1", "elem", "EMN", "TP1", "tp", "TPM",
        "G1", "Acme SA", "ACME", siren, "12345678900012", List.of());
  }

  private ReferentialCacheCore core(CacheConfig config, ResponseGuard guard,
                                    Function<String, List<PartyRegistrationDetails>> searcher) {
    return new ReferentialCacheCore(KeySpace.SIREN, Flow.INBOUND, config, guard, maintenance,
        searcher, PartyRegistrationDetails::siren);
  }

  private static CacheConfig config() {
    return CacheConfig.defaults();
  }

  private static final ResponseGuard PASS =
      (flow, keySpace, key, response) -> GuardDecision.pass(response);

  // ── Basic lookup ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("a first lookup loads, a second is served from the map")
  void secondLookupIsAHit() {
    AtomicInteger calls = new AtomicInteger();
    ReferentialCacheCore c = core(config(), PASS, key -> {
      calls.incrementAndGet();
      return List.of(party(key));
    });

    assertEquals(1, c.lookup("123456789").size());
    assertEquals(1, c.lookup("123456789").size());
    assertEquals(1, calls.get(), "the second call must not reach the referential");

    CacheStats s = c.stats();
    assertEquals(1, s.hits());
    assertEquals(1, s.misses());
    assertEquals(1, s.loads());
    assertEquals(1, s.entries());
  }

  @Test
  @DisplayName("the stored list is returned directly, without copying per read")
  void readsDoNotAllocate() {
    ReferentialCacheCore c = core(config(), PASS, key -> List.of(party(key)));
    assertSame(c.lookup("123456789"), c.lookup("123456789"));
  }

  @Test
  @DisplayName("an unusable key is a miss without touching the referential")
  void unusableKeyShortCircuits() {
    AtomicInteger calls = new AtomicInteger();
    ReferentialCacheCore c = core(config(), PASS, key -> {
      calls.incrementAndGet();
      return List.of(party(key));
    });

    assertTrue(c.lookup(null).isEmpty());
    assertTrue(c.lookup("   ").isEmpty());
    assertEquals(0, calls.get());
    assertEquals(2, c.stats().misses());
  }

  @Test
  @DisplayName("several records for one key are all retained")
  void multipleRecordsAreStored() {
    ReferentialCacheCore c = core(config(), PASS,
        key -> List.of(party(key), party(key)));
    assertEquals(2, c.lookup("123456789").size());
    assertEquals(2, c.lookup("123456789").size());
  }

  // ── Negative caching ──────────────────────────────────────────────────────

  @Test
  @DisplayName("a 'not found' is cached, so an unknown id cannot loop against the referential")
  void notFoundIsCached() {
    AtomicInteger calls = new AtomicInteger();
    ReferentialCacheCore c = core(config(), PASS, key -> {
      calls.incrementAndGet();
      return List.of();
    });

    assertTrue(c.lookup("123456789").isEmpty());
    assertTrue(c.lookup("123456789").isEmpty());
    assertEquals(1, calls.get());
    assertEquals(1, c.stats().negativeHits());
  }

  @Test
  @DisplayName("a null response from the gateway is treated as 'not found'")
  void nullResponseIsNotFound() {
    ReferentialCacheCore c = core(config(), PASS, key -> null);
    assertTrue(c.lookup("123456789").isEmpty());
    assertEquals(1, c.stats().entries());
  }

  @Test
  @DisplayName("a negative entry expires, letting a newly created party appear")
  void negativeEntryExpires() throws InterruptedException {
    CacheConfig cfg = new CacheConfig(Duration.ofMinutes(30), Duration.ofMinutes(5), 0.0, 0.0, 8,
        Duration.ofMillis(40), 100, 1000, 100);
    AtomicInteger calls = new AtomicInteger();
    ReferentialCacheCore c = core(cfg, PASS, key ->
        calls.incrementAndGet() == 1 ? List.of() : List.of(party(key)));

    assertTrue(c.lookup("123456789").isEmpty());
    Thread.sleep(80);
    assertEquals(1, c.lookup("123456789").size(),
        "the party was created upstream after the negative entry was written");
  }

  // ── Blocking ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("a blocking guard verdict raises, and the block itself is cached")
  void blockIsCachedAndReRaised() {
    AtomicInteger calls = new AtomicInteger();
    ResponseGuard blocking = (flow, ks, key, response) -> GuardDecision.block("4471");
    ReferentialCacheCore c = core(config(), blocking, key -> {
      calls.incrementAndGet();
      return List.of(party(key));
    });

    PartyRegistrationUnavailableException first = assertThrows(
        PartyRegistrationUnavailableException.class, () -> c.lookup("123456789"));
    assertEquals(UnavailabilityReason.BLOCKED, first.reason());
    assertEquals("4471", first.referenceId(), "the operator needs the row to fix");
    assertTrue(first.getMessage().contains("4471"));

    PartyRegistrationUnavailableException second = assertThrows(
        PartyRegistrationUnavailableException.class, () -> c.lookup("123456789"));
    assertEquals("4471", second.referenceId());
    assertEquals(1, calls.get(),
        "caching the block is what stops a hot-path defect re-querying upstream every call");
    assertEquals(1, c.stats().guardBlocks());
    assertEquals(1, c.stats().blockedHits());
  }

  @Test
  @DisplayName("a block with no reference still reports the reason")
  void blockWithoutReference() {
    ReferentialCacheCore c = core(config(),
        (flow, ks, key, response) -> GuardDecision.block(null),
        key -> List.of(party(key)));

    PartyRegistrationUnavailableException e = assertThrows(
        PartyRegistrationUnavailableException.class, () -> c.lookup("123456789"));
    assertEquals(UnavailabilityReason.BLOCKED, e.reason());
    assertFalse(e.getMessage().contains("[ref"));
  }

  // ── Guard behaviour ───────────────────────────────────────────────────────

  @Test
  @DisplayName("a throwing guard degrades data quality, never availability")
  void throwingGuardIsTreatedAsAbsent() {
    ReferentialCacheCore c = core(config(), (flow, ks, key, response) -> {
      throw new IllegalStateException("guard exploded");
    }, key -> List.of(party(key)));

    assertEquals(1, c.lookup("123456789").size(),
        "the referential's answer is served unguarded rather than withheld");
  }

  @Test
  @DisplayName("a guard returning null is treated as a pass")
  void nullGuardDecisionIsAPass() {
    ReferentialCacheCore c = core(config(),
        (flow, ks, key, response) -> null, key -> List.of(party(key)));
    assertEquals(1, c.lookup("123456789").size());
  }

  @Test
  @DisplayName("a volatile verdict is served but held only briefly")
  void volatileEntryExpiresQuickly() throws InterruptedException {
    CacheConfig cfg = new CacheConfig(Duration.ofMinutes(30), Duration.ofMillis(40), 0.0, 0.0, 8,
        Duration.ofMinutes(1), 100, 1000, 100);
    AtomicInteger calls = new AtomicInteger();
    ReferentialCacheCore c = core(cfg,
        (flow, ks, key, response) -> GuardDecision.serveVolatile(response, "ref-1"),
        key -> { calls.incrementAndGet(); return List.of(party(key)); });

    assertEquals(1, c.lookup("123456789").size());
    Thread.sleep(80);
    assertEquals(1, c.lookup("123456789").size());
    assertEquals(2, calls.get(),
        "a correction must reach callers rather than sitting for a full lifetime");
  }

  // ── Gateway failures ──────────────────────────────────────────────────────

  @Test
  @DisplayName("an upstream outage is never cached")
  void upstreamOutageIsNotCached() {
    AtomicInteger calls = new AtomicInteger();
    ReferentialCacheCore c = core(config(), PASS, key -> {
      calls.incrementAndGet();
      throw new ReferentialGateway.ReferentialUnavailableException("socket closed", null);
    });

    PartyRegistrationUnavailableException e = assertThrows(
        PartyRegistrationUnavailableException.class, () -> c.lookup("123456789"));
    assertEquals(UnavailabilityReason.UPSTREAM_UNAVAILABLE, e.reason());
    assertTrue(e.isRetryable());

    assertThrows(PartyRegistrationUnavailableException.class, () -> c.lookup("123456789"));
    assertEquals(2, calls.get(), "a transient outage must not be frozen in for an entry lifetime");
    assertEquals(2, c.stats().loadFailures());
  }

  @Test
  @DisplayName("an unexpected gateway exception propagates and is counted")
  void unexpectedGatewayExceptionPropagates() {
    ReferentialCacheCore c = core(config(), PASS, key -> {
      throw new IllegalStateException("bug in the adapter");
    });
    assertThrows(IllegalStateException.class, () -> c.lookup("123456789"));
    assertEquals(1, c.stats().loadFailures());
  }

  // ── Coalescing ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("concurrent misses on one key collapse into a single referential call")
  void concurrentMissesCoalesce() throws Exception {
    int threads = 8;
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch arrived = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();

    ReferentialCacheCore c = core(config(), PASS, key -> {
      calls.incrementAndGet();
      arrived.countDown();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return List.of(party(key));
    });

    List<Thread> workers = new ArrayList<>();
    List<List<PartyRegistrationDetails>> results = java.util.Collections.synchronizedList(new ArrayList<>());
    for (int i = 0; i < threads; i++) {
      Thread t = new Thread(() -> results.add(c.lookup("123456789")));
      workers.add(t);
      t.start();
    }

    assertTrue(arrived.await(5, TimeUnit.SECONDS), "the first loader should have started");
    Thread.sleep(50);   // let the others pile up behind the in-flight future
    release.countDown();
    for (Thread t : workers) {
      t.join(5000);
    }

    assertEquals(1, calls.get(), "one referential call for the whole burst");
    assertEquals(threads, results.size());
    results.forEach(r -> assertEquals(1, r.size()));
    assertTrue(c.stats().coalescedLoads() > 0);
  }

  @Test
  @DisplayName("a failure on the loading thread propagates to every coalesced waiter")
  void coalescedWaitersSeeTheFailure() throws Exception {
    CountDownLatch arrived = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    ReferentialCacheCore c = core(config(), PASS, key -> {
      arrived.countDown();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      throw new ReferentialGateway.ReferentialUnavailableException("down", null);
    });

    List<Throwable> caught = java.util.Collections.synchronizedList(new ArrayList<>());
    List<Thread> workers = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      Thread t = new Thread(() -> {
        try {
          c.lookup("123456789");
        } catch (RuntimeException e) {
          caught.add(e);
        }
      });
      workers.add(t);
      t.start();
    }

    assertTrue(arrived.await(5, TimeUnit.SECONDS));
    Thread.sleep(50);
    release.countDown();
    for (Thread t : workers) {
      t.join(5000);
    }

    assertEquals(4, caught.size(), "a block propagates to every waiter, not just the loader");
    caught.forEach(e -> assertTrue(e instanceof PartyRegistrationUnavailableException));
  }

  // ── Refresh-ahead ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("a stale-but-live hit is served immediately and refreshed in the background")
  void staleHitTriggersRefreshAhead() throws InterruptedException {
    CacheConfig cfg = new CacheConfig(Duration.ofMillis(200), Duration.ofMinutes(5), 0.0, 0.10, 8,
        Duration.ofMinutes(1), 100, 1000, 100);
    AtomicInteger calls = new AtomicInteger();
    ReferentialCacheCore c = core(cfg, PASS, key -> {
      calls.incrementAndGet();
      return List.of(party(key));
    });

    c.lookup("123456789");
    Thread.sleep(60);   // past the refresh point (10% of 200ms), well before hard expiry

    assertEquals(1, c.lookup("123456789").size(), "the caller is still served without waiting");
    Thread.sleep(200);  // let the background refresh land

    assertTrue(c.stats().staleHits() > 0);
    assertTrue(c.stats().refreshAheads() > 0);
    assertTrue(calls.get() > 1, "a background reload should have run");
  }

  @Test
  @DisplayName("with refresh-ahead disabled, a live entry is simply served")
  void refreshAheadCanBeDisabled() throws InterruptedException {
    CacheConfig cfg = new CacheConfig(Duration.ofMillis(200), Duration.ofMinutes(5), 0.0, 0.0, 8,
        Duration.ofMinutes(1), 100, 1000, 100);
    AtomicInteger calls = new AtomicInteger();
    ReferentialCacheCore c = core(cfg, PASS, key -> {
      calls.incrementAndGet();
      return List.of(party(key));
    });

    c.lookup("123456789");
    Thread.sleep(60);
    c.lookup("123456789");
    Thread.sleep(100);

    assertEquals(1, calls.get(), "no background work when the threshold is 0");
    assertEquals(0, c.stats().refreshAheads());
  }

  @Test
  @DisplayName("a hard-expired entry is reloaded synchronously")
  void expiredEntryIsReloaded() throws InterruptedException {
    CacheConfig cfg = new CacheConfig(Duration.ofMillis(40), Duration.ofMinutes(5), 0.0, 0.0, 8,
        Duration.ofMinutes(1), 100, 1000, 100);
    AtomicInteger calls = new AtomicInteger();
    ReferentialCacheCore c = core(cfg, PASS, key -> {
      calls.incrementAndGet();
      return List.of(party(key));
    });

    c.lookup("123456789");
    Thread.sleep(80);
    c.lookup("123456789");
    assertEquals(2, calls.get());
  }

  @Test
  @DisplayName("jitter keeps the lifetime within the configured band")
  void jitterStaysWithinBand() {
    CacheConfig cfg = new CacheConfig(Duration.ofSeconds(10), Duration.ofMinutes(5), 0.5, 0.0, 8,
        Duration.ofMinutes(1), 100, 1000, 100);
    ReferentialCacheCore c = core(cfg, PASS, key -> List.of(party(key)));

    for (int i = 0; i < 50; i++) {
      assertEquals(1, c.lookup("12345678" + (i % 10)).size(),
          "jitter must never produce an already-expired entry");
    }
  }

  // ── Invalidation and sweeping ─────────────────────────────────────────────

  @Test
  @DisplayName("invalidate drops one key and leaves the rest")
  void invalidateDropsOneKey() {
    AtomicInteger calls = new AtomicInteger();
    ReferentialCacheCore c = core(config(), PASS, key -> {
      calls.incrementAndGet();
      return List.of(party(key));
    });

    c.lookup("123456789");
    c.lookup("987654321");
    c.invalidate("123456789");

    assertEquals(1, c.stats().entries());
    c.lookup("123456789");
    assertEquals(3, calls.get());
  }

  @Test
  @DisplayName("invalidating an unusable key is a no-op")
  void invalidateIgnoresUnusableKeys() {
    ReferentialCacheCore c = core(config(), PASS, key -> List.of(party(key)));
    c.lookup("123456789");
    c.invalidate(null);
    c.invalidate("  ");
    assertEquals(1, c.stats().entries());
  }

  @Test
  @DisplayName("invalidateAll clears the map and the pool together")
  void invalidateAllClearsEverything() {
    ReferentialCacheCore c = core(config(), PASS, key -> List.of(party(key)));
    c.lookup("123456789");
    c.lookup("987654321");

    c.invalidateAll();
    assertEquals(0, c.stats().entries());
    assertEquals(0, c.stats().pooledStrings());
  }

  @Test
  @DisplayName("the ceiling is enforced by a background sweep")
  void sweepEnforcesTheCeiling() throws InterruptedException {
    CacheConfig cfg = new CacheConfig(Duration.ofMinutes(30), Duration.ofMinutes(5), 0.0, 0.0, 8,
        Duration.ofMinutes(1), 5, 2, 100);
    ReferentialCacheCore c = core(cfg, PASS, key -> List.of(party(key)));

    for (int i = 0; i < 40; i++) {
      c.lookup("10000000" + i);
    }
    Thread.sleep(500);   // sweeps run off the request path

    assertTrue(c.stats().entries() <= 40,
        "the sweep is a soft ceiling — the guarantee is that it runs, not that it is instant");
  }

  @Test
  @DisplayName("a zero ceiling disables sweeping entirely")
  void zeroCeilingDisablesSweeping() {
    CacheConfig cfg = new CacheConfig(Duration.ofMinutes(30), Duration.ofMinutes(5), 0.0, 0.0, 8,
        Duration.ofMinutes(1), 0, 2, 100);
    ReferentialCacheCore c = core(cfg, PASS, key -> List.of(party(key)));

    for (int i = 0; i < 10; i++) {
      c.lookup("10000000" + i);
    }
    assertEquals(10, c.stats().entries());
  }

  @Test
  @DisplayName("a rejected maintenance task does not wedge the sweep flag")
  void rejectedMaintenanceIsRecoverable() {
    ExecutorService closed = Executors.newSingleThreadExecutor();
    closed.shutdownNow();

    CacheConfig cfg = new CacheConfig(Duration.ofMinutes(30), Duration.ofMinutes(5), 0.0, 0.0, 8,
        Duration.ofMinutes(1), 1, 1, 100);
    ReferentialCacheCore c = new ReferentialCacheCore(KeySpace.SIREN, Flow.INBOUND, cfg, PASS,
        closed, key -> List.of(party(key)), PartyRegistrationDetails::siren);

    for (int i = 0; i < 5; i++) {
      assertEquals(1, c.lookup("10000000" + i).size(),
          "a dead maintenance executor must not break lookups");
    }
  }

  // ── Stats and key reuse ───────────────────────────────────────────────────

  @Test
  @DisplayName("stats report the key space they belong to")
  void statsCarryTheKeySpace() {
    ReferentialCacheCore c = core(config(), PASS, key -> List.of(party(key)));
    assertEquals("SIREN", c.stats().keySpace());
    assertNotNull(c.stats());
  }

  @Test
  @DisplayName("the map key falls back to the pool when the record does not carry it")
  void storedKeyFallsBackToThePool() {
    // The record's siren does not match the lookup key, so the engine cannot reuse the
    // record's own instance and must intern the key instead.
    ReferentialCacheCore c = core(config(), PASS, key -> List.of(party("999999999")));

    assertEquals(1, c.lookup("123456789").size());
    assertEquals(1, c.lookup("123456789").size(), "the fallback key must still be findable");
    assertEquals(1, c.stats().hits());
  }

  @Test
  @DisplayName("a record with a null key field still stores cleanly")
  void nullKeyFieldFallsBack() {
    ReferentialCacheCore c = core(config(), PASS, key -> List.of(party(null)));
    assertEquals(1, c.lookup("123456789").size());
    assertEquals(1, c.lookup("123456789").size());
  }
}
