package com.sg.domain.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domaininterface.model.party.Flow;
import com.sg.domaininterface.model.party.KeySpace;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.model.party.RegistrationType;
import com.sg.domaininterface.port.out.GuardDecision;
import com.sg.domaininterface.port.out.ReferentialGateway;
import com.sg.domaininterface.port.out.ResponseGuard;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The remaining paths: interrupt handling on close, the SIRET branch of the key-space router,
 * refresh-ahead back-pressure, deep sweeping, and the coalesced-failure variants.
 *
 * <p>Kept separate from the main suites because several of these need deliberately hostile
 * setups — a pre-interrupted thread, a dead executor, an exhausted semaphore — that would
 * obscure the intent of the straightforward tests.
 */
class CacheEdgePathsTest {

  private final ExecutorService maintenance = Executors.newVirtualThreadPerTaskExecutor();

  @AfterEach
  void shutdown() {
    maintenance.shutdownNow();
    Thread.interrupted();   // clear the flag so a deliberate interrupt cannot leak between tests
  }

  private static final ResponseGuard PASS =
      (flow, keySpace, key, response) -> GuardDecision.pass(response);

  private static PartyRegistrationDetails party(String siren) {
    return new PartyRegistrationDetails("E1", "elem", "EMN", "TP1", "tp", "TPM",
        "G1", "Acme SA", "ACME", siren, "12345678900012", List.of());
  }

  private static ReferentialGateway gateway(List<PartyRegistrationDetails> response) {
    return new ReferentialGateway() {
      @Override public List<PartyRegistrationDetails> searchByBdrId(String bdrId) {
        return response;
      }
      @Override public List<PartyRegistrationDetails> searchByRegistration(
          String id, RegistrationType type) {
        return response;
      }
    };
  }

  // ── close() interrupt handling ────────────────────────────────────────────

  /**
   * A gateway that parks inside the search, so a refresh-ahead task is still running when
   * {@code close()} is called. Without an in-flight task the executor terminates instantly and
   * {@code awaitTermination} returns before it ever checks the interrupt flag.
   */
  private static ReferentialGateway parkingGateway(CountDownLatch entered, CountDownLatch release) {
    return new ReferentialGateway() {
      private final AtomicInteger calls = new AtomicInteger();

      private List<PartyRegistrationDetails> park() {
        if (calls.incrementAndGet() > 1) {
          entered.countDown();
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        return List.of(party("123456789"));
      }

      @Override public List<PartyRegistrationDetails> searchByBdrId(String bdrId) {
        return park();
      }
      @Override public List<PartyRegistrationDetails> searchByRegistration(
          String id, RegistrationType type) {
        return park();
      }
    };
  }

  /** Short TTL + eager refresh so the second lookup dispatches a background reload. */
  private static CacheConfig eagerRefresh() {
    return new CacheConfig(Duration.ofMillis(150), Duration.ofMinutes(5), 0.0, 0.05, 8,
        Duration.ofMinutes(1), 1000, 10_000, 100);
  }

  @Test
  @DisplayName("closing the inbound cache on an interrupted thread re-sets the flag")
  void inboundCloseRestoresTheInterruptFlag() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    InboundPartyRegistrationCache cache = new InboundPartyRegistrationCache(
        parkingGateway(entered, release), eagerRefresh(), PASS);

    cache.findBySiren("123456789");
    Thread.sleep(40);
    cache.findBySiren("123456789");                       // dispatches the parked refresh
    assertTrue(entered.await(5, TimeUnit.SECONDS), "a maintenance task should be running");

    Thread.currentThread().interrupt();
    cache.close();                                        // awaitTermination must block, then throw

    assertTrue(Thread.interrupted(),
        "swallowing an interrupt would strand a shutting-down container");
    release.countDown();
  }

  @Test
  @DisplayName("closing the outbound cache on an interrupted thread re-sets the flag")
  void outboundCloseRestoresTheInterruptFlag() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    OutboundPartyRegistrationCache cache = new OutboundPartyRegistrationCache(
        parkingGateway(entered, release), eagerRefresh(), PASS);

    cache.findByBdrId("BDR-1");
    Thread.sleep(40);
    cache.findByBdrId("BDR-1");
    assertTrue(entered.await(5, TimeUnit.SECONDS));

    Thread.currentThread().interrupt();
    cache.close();

    assertTrue(Thread.interrupted());
    release.countDown();
  }

  @Test
  @DisplayName("a stale hit while the same key is already loading does not dispatch a second refresh")
  void refreshAheadSkipsKeysAlreadyInFlight() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();

    ReferentialCacheCore core = new ReferentialCacheCore(KeySpace.SIREN, Flow.INBOUND,
        eagerRefresh(), PASS, maintenance, key -> {
          if (calls.incrementAndGet() > 1) {
            entered.countDown();
            try {
              release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          return List.of(party(key));
        }, PartyRegistrationDetails::siren);

    core.lookup("123456789");
    Thread.sleep(40);
    core.lookup("123456789");                             // dispatches refresh #1
    assertTrue(entered.await(5, TimeUnit.SECONDS));

    core.lookup("123456789");                             // still stale, but a load is in flight
    core.lookup("123456789");

    assertEquals(1, core.stats().refreshAheads(),
        "the in-flight map deduplicates a burst of stale hits down to one refresh");
    release.countDown();
  }

  // ── Key-space routing ─────────────────────────────────────────────────────

  @Test
  @DisplayName("invalidate routes to the SIRET core as well as the SIREN one")
  void invalidateRoutesToSiret() {
    AtomicInteger siretCalls = new AtomicInteger();
    ReferentialGateway gw = new ReferentialGateway() {
      @Override public List<PartyRegistrationDetails> searchByBdrId(String bdrId) {
        return List.of();
      }
      @Override public List<PartyRegistrationDetails> searchByRegistration(
          String id, RegistrationType type) {
        if (type == RegistrationType.SIRET) {
          siretCalls.incrementAndGet();
        }
        return List.of(party("123456789"));
      }
    };

    try (InboundPartyRegistrationCache cache =
             new InboundPartyRegistrationCache(gw, CacheConfig.defaults(), PASS)) {
      cache.findBySiret("12345678900012");
      cache.invalidate(KeySpace.SIRET, "12345678900012");
      cache.findBySiret("12345678900012");

      assertEquals(2, siretCalls.get(), "the SIRET entry should have been dropped and reloaded");
    }
  }

  // ── Refresh-ahead back-pressure ───────────────────────────────────────────

  @Test
  @DisplayName("refresh-ahead is capped, so it cannot become the spike it exists to prevent")
  void refreshAheadRespectsItsPermitCeiling() throws Exception {
    CountDownLatch hold = new CountDownLatch(1);
    CountDownLatch refreshing = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();

    // One permit, so the second stale key finds none available and returns without dispatching.
    CacheConfig cfg = new CacheConfig(Duration.ofMillis(300), Duration.ofMinutes(5), 0.0, 0.05, 1,
        Duration.ofMinutes(1), 1000, 10_000, 100);

    ReferentialCacheCore core = new ReferentialCacheCore(KeySpace.SIREN, Flow.INBOUND, cfg, PASS,
        maintenance, key -> {
          if (calls.incrementAndGet() > 2) {      // block only the background refresh
            refreshing.countDown();
            try {
              hold.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          return List.of(party(key));
        }, PartyRegistrationDetails::siren);

    core.lookup("111111111");
    core.lookup("222222222");
    Thread.sleep(60);                              // both are now stale but live

    core.lookup("111111111");                      // takes the only permit
    assertTrue(refreshing.await(5, TimeUnit.SECONDS));
    core.lookup("222222222");                      // no permit left — must return immediately

    assertEquals(1, core.stats().refreshAheads(),
        "the second stale hit is served without dispatching a refresh");
    hold.countDown();
  }

  @Test
  @DisplayName("a refresh-ahead whose reload fails leaves the caller's answer intact")
  void refreshAheadFailureIsSwallowed() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    CacheConfig cfg = new CacheConfig(Duration.ofMillis(200), Duration.ofMinutes(5), 0.0, 0.05, 8,
        Duration.ofMinutes(1), 1000, 10_000, 100);

    ReferentialCacheCore core = new ReferentialCacheCore(KeySpace.SIREN, Flow.INBOUND, cfg, PASS,
        maintenance, key -> {
          if (calls.incrementAndGet() > 1) {
            throw new ReferentialGateway.ReferentialUnavailableException("down", null);
          }
          return List.of(party(key));
        }, PartyRegistrationDetails::siren);

    core.lookup("123456789");
    Thread.sleep(60);

    assertEquals(1, core.lookup("123456789").size(),
        "the caller already has a usable answer; a failed background reload must not surface");
    Thread.sleep(200);
  }

  @Test
  @DisplayName("a dead maintenance executor releases the permit rather than leaking it")
  void rejectedRefreshReleasesThePermit() throws Exception {
    ExecutorService dead = Executors.newSingleThreadExecutor();
    dead.shutdownNow();

    CacheConfig cfg = new CacheConfig(Duration.ofMillis(150), Duration.ofMinutes(5), 0.0, 0.05, 1,
        Duration.ofMinutes(1), 1000, 10_000, 100);
    ReferentialCacheCore core = new ReferentialCacheCore(KeySpace.SIREN, Flow.INBOUND, cfg, PASS,
        dead, key -> List.of(party(key)), PartyRegistrationDetails::siren);

    core.lookup("123456789");
    Thread.sleep(40);

    // Two stale hits: if the rejected dispatch leaked its permit, the second would find none.
    assertEquals(1, core.lookup("123456789").size());
    assertEquals(1, core.lookup("123456789").size());
    assertEquals(2, core.stats().refreshAheads(),
        "a rejected execution must return the permit it took");
  }

  // ── Volatile entries skip refresh-ahead ───────────────────────────────────

  @Test
  @DisplayName("a volatile entry is never refresh-ahead'd — freshness beats latency there")
  void volatileEntriesSkipRefreshAhead() throws Exception {
    CacheConfig cfg = new CacheConfig(Duration.ofSeconds(10), Duration.ofMillis(120), 0.0, 0.05, 8,
        Duration.ofMinutes(1), 1000, 10_000, 100);
    AtomicInteger calls = new AtomicInteger();

    ReferentialCacheCore core = new ReferentialCacheCore(KeySpace.SIREN, Flow.INBOUND, cfg,
        (flow, ks, key, response) -> GuardDecision.serveVolatile(response, "ref-1"),
        maintenance, key -> {
          calls.incrementAndGet();
          return List.of(party(key));
        }, PartyRegistrationDetails::siren);

    core.lookup("123456789");
    Thread.sleep(40);
    core.lookup("123456789");

    assertEquals(0, core.stats().refreshAheads(),
        "a volatile entry's whole point is to expire and be reloaded, not to be kept warm");
    assertEquals(1, calls.get());
  }

  // ── Deep sweep ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("when expiry alone is not enough, live entries are evicted down to the ceiling")
  void deepSweepEvictsLiveEntries() throws Exception {
    // Long TTL so nothing expires: the sweep must fall through to evicting live entries.
    CacheConfig cfg = new CacheConfig(Duration.ofHours(1), Duration.ofMinutes(5), 0.0, 0.0, 8,
        Duration.ofMinutes(1), 3, 1, 100);
    ReferentialCacheCore core = new ReferentialCacheCore(KeySpace.SIREN, Flow.INBOUND, cfg, PASS,
        maintenance, key -> List.of(party(key)), PartyRegistrationDetails::siren);

    for (int i = 0; i < 30; i++) {
      core.lookup("10000000" + i);
    }

    // The ceiling is soft in a way worth being precise about: a sweep already in flight makes
    // subsequent maybeSweep calls return immediately rather than queueing, so inserts landing
    // during a sweep outlive it. Convergence therefore needs a quiet insert to re-arm the
    // check — which is exactly how the cache behaves under real traffic.
    long deadline = System.currentTimeMillis() + 10_000;
    while (core.stats().entries() > 3 && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
      core.lookup("999999999");   // re-arms the sweep once the in-flight one has finished
    }

    assertTrue(core.stats().entries() <= 4,
        "nothing expired here, so reaching the ceiling proves live entries were evicted; "
            + "entries=" + core.stats().entries());
    assertTrue(core.stats().entries() < 30, "the sweep must actually have run");
  }

  @Test
  @DisplayName("expired entries are swept before any live one is touched")
  void expiredEntriesAreSweptFirst() throws Exception {
    CacheConfig cfg = new CacheConfig(Duration.ofMillis(60), Duration.ofMinutes(5), 0.0, 0.0, 8,
        Duration.ofMinutes(1), 2, 1, 100);
    ReferentialCacheCore core = new ReferentialCacheCore(KeySpace.SIREN, Flow.INBOUND, cfg, PASS,
        maintenance, key -> List.of(party(key)), PartyRegistrationDetails::siren);

    for (int i = 0; i < 10; i++) {
      core.lookup("10000000" + i);
    }
    Thread.sleep(150);          // everything is now expired
    core.lookup("999999999");   // triggers a sweep

    long deadline = System.currentTimeMillis() + 5000;
    while (core.stats().entries() > 2 && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(core.stats().entries() <= 2);
  }

  // ── Coalesced failure variants ────────────────────────────────────────────

  @Test
  @DisplayName("an Error on the loading thread reaches the coalesced waiters as an Error")
  void coalescedWaitersSeeAnError() throws Exception {
    CountDownLatch arrived = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    ReferentialCacheCore core = new ReferentialCacheCore(KeySpace.SIREN, Flow.INBOUND,
        CacheConfig.defaults(), PASS, maintenance, key -> {
          arrived.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          throw new AssertionError("catastrophic adapter failure");
        }, PartyRegistrationDetails::siren);

    List<Throwable> caught = java.util.Collections.synchronizedList(new ArrayList<>());
    List<Thread> workers = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      Thread t = new Thread(() -> {
        try {
          core.lookup("123456789");
        } catch (Throwable e) {
          caught.add(e);
        }
      });
      workers.add(t);
      t.start();
    }

    assertTrue(arrived.await(5, TimeUnit.SECONDS));
    Thread.sleep(60);
    release.countDown();
    for (Thread t : workers) {
      t.join(5000);
    }

    assertEquals(3, caught.size());
    assertTrue(caught.stream().allMatch(e -> e instanceof AssertionError),
        "an Error must stay an Error rather than being wrapped into a CompletionException");
  }

  // ── StringPool concurrent insert ──────────────────────────────────────────

  /**
   * Threads interning the same brand-new value must all come away with one instance.
   *
   * <p><b>Why this runs many rounds instead of one.</b> The interesting path is the
   * {@code putIfAbsent} loser: a thread that read the pool, found nothing, and by the time it
   * wrote had been beaten to it. Nothing forces that interleaving — a single round can finish
   * with the first thread far enough ahead that every other one takes the fast read path and the
   * loser arm never executes. That is not a passing test proving anything; it is a test that
   * happened to miss. Rounds of a fresh value with a barrier release make the collision
   * reliable, and each round is a real assertion in its own right.
   *
   * <p>A fresh value per round matters: reusing one would be answered from the pool after the
   * first round and every later round would exercise nothing.
   */
  @Test
  @DisplayName("threads interning the same new value converge on one instance")
  void concurrentInternConvergesOnOneInstance() throws Exception {
    int threads = 16;
    int rounds = 200;
    StringPool pool = new StringPool(rounds * 2);
    ExecutorService workers = Executors.newFixedThreadPool(threads);

    try {
      for (int round = 0; round < rounds; round++) {
        String value = "12345" + round;
        CyclicBarrier gate = new CyclicBarrier(threads);
        List<Future<String>> futures = new ArrayList<>(threads);

        for (int i = 0; i < threads; i++) {
          futures.add(workers.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            // A fresh instance per thread, so only the pool can make them identical.
            return pool.canonicalize(new String(value));
          }));
        }

        String first = futures.get(0).get(5, TimeUnit.SECONDS);
        for (Future<String> f : futures) {
          assertSame(first, f.get(5, TimeUnit.SECONDS),
              "the putIfAbsent loser must adopt the winner's instance, or dedup silently fails");
        }
      }
    } finally {
      workers.shutdownNow();
    }

    assertEquals(rounds, pool.size(), "one pooled instance per distinct value, and no more");
  }

  @Test
  @DisplayName("interning is idempotent for a value already in the pool")
  void internIsIdempotent() {
    StringPool pool = new StringPool(10);
    String canonical = pool.canonicalize(new String("123456789"));
    assertSame(canonical, pool.canonicalize(new String("123456789")));
    assertSame(canonical, pool.canonicalize(canonical));
    assertFalse(pool.hitCount() == 0);
  }
}
