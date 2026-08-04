package com.sg.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.port.out.QuarantineRecord;
import com.sg.domaininterface.port.out.QuarantineStatus;
import com.sg.domaininterface.port.out.QuarantineStore;
import com.sg.domaininterface.rule.party.AnomalyType;
import com.sg.domaininterface.rule.party.Servability;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The poller: what it evicts, and that it survives a store that misbehaves.
 *
 * <p>Split out when the poller moved into this module. It shared a file with the quarantine
 * guard's tests, and the two had a store stub in common and nothing else — the guard decides
 * whether a response is servable, which is policy; the poller drives eviction on a timer, which
 * is an adapter.
 */
class QuarantinePollerTest {

  private static QuarantineRecord row(String lookupKey, Instant updatedAt) {
    return new QuarantineRecord(1L, "SIREN", lookupKey, "fp",
        Set.of(AnomalyType.MISSING_SIRET), Servability.SERVABLE, null, null,
        QuarantineStatus.CORRECTED, Instant.EPOCH, updatedAt, null, null, null);
  }

  /** A store that answers {@code findChangedSince} and nothing else. */
  private static class StubStore implements QuarantineStore {
    @Override public Optional<QuarantineRecord> findActive(String ks, String k) {
      return Optional.empty();
    }
    @Override public UpsertResult upsert(QuarantineRecord r) {
      throw new UnsupportedOperationException("the poller only reads");
    }
    @Override public void markNotified(long id, Instant at) { /* not reached */ }
    @Override public QuarantineRecord applyCorrection(long id,
        List<PartyRegistrationDetails> c, String by, String n) { return null; }
    @Override public void softDelete(long id, String by) { /* not reached */ }
    @Override public List<QuarantineRecord> findChangedSince(Instant since, int limit) {
      return List.of();
    }
    @Override public List<QuarantineRecord> findByStatus(QuarantineStatus s, int limit) {
      return List.of();
    }
  }

  @Test
  @DisplayName("changed rows are evicted from the cache, by key space and key")
  void changedRowsAreEvicted() throws Exception {
    StubStore store = new StubStore() {
      volatile boolean served;
      @Override public List<QuarantineRecord> findChangedSince(Instant since, int limit) {
        if (served) return List.of();
        served = true;
        return List.of(row("111111111", Instant.now()), row("222222222", Instant.now()));
      }
    };

    List<String> evicted = new CopyOnWriteArrayList<>();
    CountDownLatch both = new CountDownLatch(2);

    try (QuarantinePoller poller = new QuarantinePoller(store, Duration.ofMillis(30),
        (keySpace, key) -> {
          evicted.add(keySpace + ":" + key);
          both.countDown();
        })) {
      poller.start();
      assertTrue(both.await(5, TimeUnit.SECONDS), "both corrected rows must be evicted");
    }

    assertTrue(evicted.contains("SIREN:111111111"));
    assertTrue(evicted.contains("SIREN:222222222"));
  }

  @Test
  @DisplayName("a full batch is still evicted in its entirety")
  void fullBatchIsEvicted() throws Exception {
    // 500 is the poller's batch ceiling. Reaching it means more changes are waiting, which is
    // reported — but every row in the batch still has to be evicted first, or the cache keeps
    // serving a party whose registration has already been corrected.
    List<QuarantineRecord> full = new ArrayList<>(500);
    for (int i = 0; i < 500; i++) {
      full.add(row("key-" + i, Instant.now()));
    }

    StubStore store = new StubStore() {
      volatile boolean served;
      @Override public List<QuarantineRecord> findChangedSince(Instant since, int limit) {
        if (served) return List.of();
        served = true;
        return full;
      }
    };

    CountDownLatch allEvicted = new CountDownLatch(500);
    try (QuarantinePoller poller = new QuarantinePoller(store, Duration.ofMillis(30),
        (ks, key) -> allEvicted.countDown())) {
      poller.start();
      assertTrue(allEvicted.await(5, TimeUnit.SECONDS),
          "every row in a full batch must still be evicted");
    }
  }

  @Test
  @DisplayName("a row with no updated_at does not stall the watermark")
  void nullUpdatedAtIsTolerated() throws Exception {
    // updated_at is written by the database clock and could be absent on a legacy row. Using it
    // blindly would put null into the watermark and break every later poll.
    StubStore store = new StubStore() {
      volatile boolean served;
      @Override public List<QuarantineRecord> findChangedSince(Instant since, int limit) {
        if (served) return List.of();
        served = true;
        return List.of(row("333333333", null));
      }
    };

    CountDownLatch evicted = new CountDownLatch(1);
    try (QuarantinePoller poller = new QuarantinePoller(store, Duration.ofMillis(30),
        (ks, key) -> evicted.countDown())) {
      poller.start();
      assertTrue(evicted.await(5, TimeUnit.SECONDS));
    }
  }

  @Test
  @DisplayName("a store that throws does not cancel the poller")
  void failureDoesNotCancelThePoller() throws Exception {
    // An exception escaping a scheduleWithFixedDelay task cancels every future run of it. The
    // poller would go silent and nothing would say so, which is why the catch inside is there.
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch recovered = new CountDownLatch(1);

    StubStore store = new StubStore() {
      @Override public List<QuarantineRecord> findChangedSince(Instant since, int limit) {
        if (calls.incrementAndGet() == 1) {
          throw new IllegalStateException("database went away");
        }
        recovered.countDown();
        return List.of();
      }
    };

    try (QuarantinePoller poller = new QuarantinePoller(store, Duration.ofMillis(30),
        (ks, key) -> { })) {
      poller.start();
      assertTrue(recovered.await(5, TimeUnit.SECONDS),
          "the poll after a failure must still run");
    }
    assertTrue(calls.get() >= 2);
  }

  @Test
  @DisplayName("an empty result advances nothing and evicts nothing")
  void emptyResultIsQuiet() throws Exception {
    AtomicInteger polls = new AtomicInteger();
    StubStore store = new StubStore() {
      @Override public List<QuarantineRecord> findChangedSince(Instant since, int limit) {
        polls.incrementAndGet();
        return List.of();
      }
    };

    List<String> evicted = new CopyOnWriteArrayList<>();
    try (QuarantinePoller poller =
             new QuarantinePoller(store, Duration.ofMillis(20), (ks, k) -> evicted.add(k))) {
      poller.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (polls.get() < 2 && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
    }

    assertTrue(polls.get() >= 2, "the poller keeps running when there is nothing to do");
    assertEquals(List.of(), evicted);
  }

  @Test
  @DisplayName("all three collaborators are mandatory")
  void collaboratorsAreMandatory() {
    StubStore store = new StubStore();
    assertThrows(NullPointerException.class,
        () -> new QuarantinePoller(null, Duration.ofMillis(10), (ks, k) -> { }));
    assertThrows(NullPointerException.class,
        () -> new QuarantinePoller(store, null, (ks, k) -> { }));
    assertThrows(NullPointerException.class,
        () -> new QuarantinePoller(store, Duration.ofMillis(10), null));
  }
}
