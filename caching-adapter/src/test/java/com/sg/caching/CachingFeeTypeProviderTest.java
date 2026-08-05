package com.sg.caching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The TTL memoiser in front of the fee-type referential.
 *
 * <p><b>The stable instance is the point, not just the saved call.</b> {@code FeeTypeMatcher}
 * builds a scoring index keyed on the map it was handed and rebuilds it when the identity
 * changes. A provider that returned an equal-but-new map on every call would make the matcher
 * rebuild its index per invoice, which is the cost this exists to avoid.
 */
class CachingFeeTypeProviderTest {

  private static final Map<String, String> REFERENTIAL =
      Map.of("F01", "CUSTODY", "F04", "BROKERAGE_PRINCIPAL");

  @Nested
  @DisplayName("caching")
  class Caching {

    @Test
    @DisplayName("the referential is loaded once and the same instance served thereafter")
    void loadsOnceWithinTheTtl() {
      AtomicInteger loads = new AtomicInteger();
      CachingFeeTypeProvider provider = new CachingFeeTypeProvider(
          () -> { loads.incrementAndGet(); return REFERENTIAL; }, Duration.ofMinutes(30));

      Map<String, String> first = provider.getFeeTypeMap();
      Map<String, String> second = provider.getFeeTypeMap();

      assertEquals(1, loads.get());
      assertSame(first, second,
          "the matcher rebuilds its index when the map identity changes, so equal-but-new "
              + "would cost an index rebuild per invoice");
      assertEquals("CUSTODY", first.get("F01"));
    }

    @Test
    @DisplayName("an expired entry is reloaded")
    void reloadsAfterTheTtl() throws InterruptedException {
      AtomicInteger loads = new AtomicInteger();
      CachingFeeTypeProvider provider = new CachingFeeTypeProvider(
          () -> { loads.incrementAndGet(); return REFERENTIAL; }, Duration.ofMillis(20));

      provider.getFeeTypeMap();
      Thread.sleep(40);
      provider.getFeeTypeMap();

      assertEquals(2, loads.get(), "a stale referential must not be served forever");
    }

    @Test
    @DisplayName("invalidate forces the next call to reload")
    void invalidateForcesReload() {
      AtomicInteger loads = new AtomicInteger();
      CachingFeeTypeProvider provider = new CachingFeeTypeProvider(
          () -> { loads.incrementAndGet(); return REFERENTIAL; }, Duration.ofHours(1));

      provider.getFeeTypeMap();
      provider.invalidate();
      provider.getFeeTypeMap();

      assertEquals(2, loads.get(),
          "a fee type added upstream should not wait an hour to become usable");
    }
  }

  @Nested
  @DisplayName("what it hands back")
  class Snapshot {

    @Test
    @DisplayName("the served map cannot be mutated by a caller")
    void servedMapIsImmutable() {
      // The matcher builds its index from this map. A caller mutating it would leave the index
      // describing a referential that no longer matches what is served.
      CachingFeeTypeProvider provider =
          new CachingFeeTypeProvider(() -> new HashMap<>(REFERENTIAL), Duration.ofHours(1));

      Map<String, String> served = provider.getFeeTypeMap();

      assertThrows(UnsupportedOperationException.class, () -> served.put("F99", "NEW"));
    }

    @Test
    @DisplayName("a mutable source is copied, so later edits do not leak through")
    void sourceIsCopied() {
      Map<String, String> mutable = new HashMap<>(REFERENTIAL);
      CachingFeeTypeProvider provider =
          new CachingFeeTypeProvider(() -> mutable, Duration.ofHours(1));

      Map<String, String> served = provider.getFeeTypeMap();
      mutable.put("F99", "SNUCK_IN");

      assertEquals(2, served.size(), "the snapshot is a snapshot");
    }

    @Test
    @DisplayName("a loader returning null yields an empty map, not a NullPointerException")
    void nullLoadIsEmpty() {
      // Better an empty referential — every fee type unresolved and reported — than an NPE
      // thrown from inside the mapper for every invoice in flight.
      CachingFeeTypeProvider provider =
          new CachingFeeTypeProvider(() -> null, Duration.ofHours(1));

      assertTrue(provider.getFeeTypeMap().isEmpty());
    }
  }

  @Nested
  @DisplayName("contract")
  class Contract {

    @Test
    @DisplayName("the loader and a positive TTL are both required")
    void argumentsAreValidated() {
      assertThrows(IllegalArgumentException.class,
          () -> new CachingFeeTypeProvider(null, Duration.ofHours(1)));
      assertThrows(IllegalArgumentException.class,
          () -> new CachingFeeTypeProvider(() -> REFERENTIAL, null));
      // Zero would mean reloading on every single call, which is the same as no cache but with
      // a lock held on the way through.
      assertThrows(IllegalArgumentException.class,
          () -> new CachingFeeTypeProvider(() -> REFERENTIAL, Duration.ZERO));
      assertThrows(IllegalArgumentException.class,
          () -> new CachingFeeTypeProvider(() -> REFERENTIAL, Duration.ofMillis(-1)));
    }
  }

  @Test
  @DisplayName("a burst of concurrent first calls loads once and agrees on one instance")
  void concurrentFirstCallsLoadOnce() throws Exception {
    // Without the double-check inside the lock, every thread arriving before the first load
    // completes would issue its own referential call — a thundering herd on exactly the
    // dependency the cache exists to protect.
    int threads = 16;
    AtomicInteger loads = new AtomicInteger();
    CachingFeeTypeProvider provider = new CachingFeeTypeProvider(
        () -> { loads.incrementAndGet(); return REFERENTIAL; }, Duration.ofHours(1));

    CyclicBarrier gate = new CyclicBarrier(threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      java.util.List<Future<Map<String, String>>> futures = new java.util.ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(pool.submit(() -> {
          gate.await(5, TimeUnit.SECONDS);
          return provider.getFeeTypeMap();
        }));
      }

      Map<String, String> first = futures.get(0).get(5, TimeUnit.SECONDS);
      for (Future<Map<String, String>> f : futures) {
        assertSame(first, f.get(5, TimeUnit.SECONDS), "every thread sees the same snapshot");
      }
    } finally {
      pool.shutdownNow();
    }

    assertEquals(1, loads.get(), "one referential call, however many threads asked at once");
  }

  @Test
  @DisplayName("invalidate during a burst still leaves everyone with a usable map")
  void invalidateUnderLoad() throws Exception {
    AtomicInteger loads = new AtomicInteger();
    CachingFeeTypeProvider provider = new CachingFeeTypeProvider(
        () -> { loads.incrementAndGet(); return REFERENTIAL; }, Duration.ofHours(1));

    CountDownLatch done = new CountDownLatch(8);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      for (int i = 0; i < 8; i++) {
        final int n = i;
        pool.submit(() -> {
          if (n % 2 == 0) {
            provider.invalidate();
          }
          assertEquals(2, provider.getFeeTypeMap().size());
          done.countDown();
        });
      }
      assertTrue(done.await(5, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }

    assertTrue(loads.get() >= 1);
  }

  @Test
  @DisplayName("a thread that waited for a refresh uses it instead of loading again")
  void secondThroughTheGateDoesNotReload() throws Exception {
    // The double-check inside the lock. Without it, every thread that queued on the monitor
    // during one slow load would run its own load the moment it got in — turning a single cold
    // start into one referential call per waiting request, which is exactly the stampede this
    // class exists to prevent.
    AtomicInteger loads = new AtomicInteger();
    CountDownLatch loaderEntered = new CountDownLatch(1);
    CountDownLatch releaseLoader = new CountDownLatch(1);

    CachingFeeTypeProvider provider = new CachingFeeTypeProvider(() -> {
      loads.incrementAndGet();
      loaderEntered.countDown();
      try {
        // Hold the lock long enough for the second thread to be waiting on it, so it enters the
        // synchronized block only after the snapshot is already fresh.
        assertTrue(releaseLoader.await(5, TimeUnit.SECONDS));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return Map.of("F01", "CUSTODY");
    }, Duration.ofMinutes(30));

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Map<String, String>> first = pool.submit(provider::getFeeTypeMap);
      assertTrue(loaderEntered.await(5, TimeUnit.SECONDS), "the first load must be in flight");

      Future<Map<String, String>> second = pool.submit(provider::getFeeTypeMap);
      // Give the second thread time to reach the monitor and block on it.
      Thread.sleep(100);
      releaseLoader.countDown();

      assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS),
          "both callers get the one snapshot, so the matcher keeps its index");
    } finally {
      pool.shutdownNow();
    }

    assertEquals(1, loads.get(), "the waiting thread reused the refresh rather than repeating it");
  }
}
