package com.sg.domain.cache;

import com.sg.domaininterface.model.party.Flow;
import com.sg.domaininterface.model.party.KeySpace;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.port.in.PartyRegistrationUnavailableException;
import com.sg.domaininterface.port.in.UnavailabilityReason;
import com.sg.domaininterface.port.out.GuardDecision;
import com.sg.domaininterface.port.out.ReferentialGateway;
import com.sg.domaininterface.port.out.ResponseGuard;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * The cache engine for one key space.
 *
 * <p><b>One map, no indirection.</b> Because key spaces are never cross-populated, a key maps
 * straight to its records — no second map from identity to payload, no per-entry id array, no
 * dereference on read. Roughly 100 bytes and one map probe cheaper per entry than a design that
 * warms across keys, and it deletes the reconciliation logic such a design needs.
 *
 * <p><b>The key costs nothing.</b> The map key is the pooled instance of the string the record
 * already holds, so it adds no retained bytes.
 *
 * <p><b>Reads allocate nothing.</b> The stored list is immutable and built once at insert, and is
 * returned directly. The single-record case is {@code List.of(x)}, which has no backing array.
 *
 * <p><b>The guard runs on the load path only</b>, so a healthy hot path never touches data-quality
 * machinery. A guard that throws is treated as absent: tracking degrades, availability does not.
 */
final class ReferentialCacheCore {

    private static final System.Logger LOG = System.getLogger(ReferentialCacheCore.class.getName());

    private final KeySpace keySpace;
    private final Flow flow;
    private final CacheConfig config;
    private final ResponseGuard guard;
    private final StringPool pool;

    /** Performs the referential search for an already-normalized key. */
    private final Function<String, List<PartyRegistrationDetails>> searcher;
    /** Reads this key space's value off a record, so the map key can share the record's instance. */
    private final Function<PartyRegistrationDetails, String> keyOfRecord;

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<List<PartyRegistrationDetails>>> inFlight =
            new ConcurrentHashMap<>();

    private final ExecutorService maintenance;
    private final Semaphore refreshPermits;
    private final AtomicInteger insertsSinceSweep = new AtomicInteger();
    private final AtomicBoolean sweeping = new AtomicBoolean();

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder negativeHits = new LongAdder();
    private final LongAdder staleHits = new LongAdder();
    private final LongAdder refreshAheads = new LongAdder();
    private final LongAdder loads = new LongAdder();
    private final LongAdder loadFailures = new LongAdder();
    private final LongAdder guardBlocks = new LongAdder();
    private final LongAdder blockedHits = new LongAdder();
    private final LongAdder coalesced = new LongAdder();

    ReferentialCacheCore(KeySpace keySpace, Flow flow, CacheConfig config, ResponseGuard guard,
                         ExecutorService maintenance,
                         Function<String, List<PartyRegistrationDetails>> searcher,
                         Function<PartyRegistrationDetails, String> keyOfRecord) {
        this.keySpace = keySpace;
        this.flow = flow;
        this.config = Objects.requireNonNull(config, "config");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
        this.searcher = searcher;
        this.keyOfRecord = keyOfRecord;
        this.pool = new StringPool(config.stringPoolMaxEntries());
        this.refreshPermits = new Semaphore(config.maxConcurrentRefreshes());
    }

    // ------------------------------------------------------------------ lookup

    List<PartyRegistrationDetails> lookup(String rawValue) {
        String key = keySpace.normalize(rawValue);
        if (key == null) {
            misses.increment();
            return List.of();
        }

        Entry entry = entries.get(key);
        if (entry != null) {
            long now = System.nanoTime();
            if (!entry.isLive(now)) {
                entries.remove(key, entry);
            } else {
                hits.increment();
                switch (entry) {
                    case Entry.Negative ignored -> {
                        negativeHits.increment();
                        return List.of();
                    }
                    case Entry.Blocked blocked -> {
                        blockedHits.increment();
                        // Re-raise without a round trip. Nothing usable was ever stored — only the
                        // fact that this key is blocked pending a correction. Caching the block is
                        // what stops a defect in a hot path re-querying upstream on every call.
                        throw unavailable(UnavailabilityReason.BLOCKED, key, blocked.referenceId());
                    }
                    case Entry.Hit hit -> {
                        if (hit.isStale(now)) {
                            staleHits.increment();
                            triggerRefreshAhead(key);
                        }
                        return hit.records();   // immutable and prebuilt: no copy, no allocation
                    }
                }
            }
        }

        misses.increment();
        return load(key);
    }

    // ------------------------------------------------------------------ entries

    /** Sealed, so the three states stay exhaustive at every use site. */
    private sealed interface Entry {

        long expiresAtNanos();

        /** Subtraction rather than comparison, so this stays correct across nanoTime overflow. */
        default boolean isLive(long now) {
            return now - expiresAtNanos() < 0;
        }

        record Hit(List<PartyRegistrationDetails> records, long refreshAtNanos, long expiresAtNanos)
                implements Entry {

            /** Live, but old enough to be worth refreshing in the background. */
            boolean isStale(long now) {
                return now - refreshAtNanos >= 0;
            }
        }

        /** Known absent. Prevents an unknown id from looping against the referential. */
        record Negative(long expiresAtNanos) implements Entry { }

        /** Known blocked. Holds no payload — nothing servable was produced. */
        record Blocked(String referenceId, long expiresAtNanos) implements Entry { }
    }

    // ------------------------------------------------------------------ loading

    /**
     * Loads a key, coalescing concurrent misses into a single referential call.
     *
     * <p>{@code putIfAbsent} rather than {@code computeIfAbsent}: the search is a blocking network
     * call, and running it inside a {@code ConcurrentHashMap} mapping function would hold a bin lock
     * for its entire duration and risk a recursive-update failure. Installing an incomplete future
     * is O(1); the call itself happens outside the map.
     */
    private List<PartyRegistrationDetails> load(String key) {
        CompletableFuture<List<PartyRegistrationDetails>> mine = new CompletableFuture<>();
        CompletableFuture<List<PartyRegistrationDetails>> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            coalesced.increment();
            return await(running);   // a block propagates to every waiter
        }

        try {
            loads.increment();
            List<PartyRegistrationDetails> fetched;
            try {
                fetched = searcher.apply(key);
            } catch (ReferentialGateway.ReferentialUnavailableException e) {
                // Never cached: a transient outage must not be frozen in for the entry lifetime.
                loadFailures.increment();
                var wrapped = new PartyRegistrationUnavailableException(
                        UnavailabilityReason.UPSTREAM_UNAVAILABLE, keySpace.name(), key,
                        e.getMessage(), null, e);
                mine.completeExceptionally(wrapped);
                throw wrapped;
            }

            List<PartyRegistrationDetails> result = fetched == null ? List.of() : fetched;
            GuardDecision decision = inspect(key, result);

            if (decision.blocked()) {
                guardBlocks.increment();
                entries.put(pool.canonicalize(key), new Entry.Blocked(decision.referenceId(),
                        System.nanoTime() + config.volatileTtl().toNanos()));
                var failure = unavailable(UnavailabilityReason.BLOCKED, key, decision.referenceId());
                mine.completeExceptionally(failure);
                throw failure;
            }

            if (decision.records().isEmpty()) {
                entries.put(pool.canonicalize(key),
                        new Entry.Negative(System.nanoTime() + config.negativeTtl().toNanos()));
                mine.complete(List.of());
                return List.of();
            }

            List<PartyRegistrationDetails> stored =
                    store(key, decision.records(), decision.volatileTtl());
            mine.complete(stored);
            return stored;

        } catch (RuntimeException | Error e) {
            if (!mine.isDone()) {
                loadFailures.increment();
                mine.completeExceptionally(e);
            }
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    /**
     * A guard failure must degrade data-quality handling, not availability, so a throwing guard is
     * treated as absent and the referential's answer is served unchanged.
     */
    private GuardDecision inspect(String key, List<PartyRegistrationDetails> result) {
        try {
            GuardDecision decision = guard.inspect(flow, keySpace, key, result);
            return decision == null ? GuardDecision.pass(result) : decision;
        } catch (RuntimeException | Error e) {
            LOG.log(Level.ERROR, "ResponseGuard failed for " + keySpace + "=" + key
                    + "; serving the referential response unguarded", e);
            return GuardDecision.pass(result);
        }
    }

    /**
     * Dispatches a background reload for a stale-but-live entry, so no request pays referential
     * latency at an expiry boundary.
     *
     * <p>Bounded by a semaphore and deduplicated through the in-flight map, so a burst of stale hits
     * on one key produces exactly one refresh. Failures are swallowed: the caller already has a
     * usable answer, and the entry will be reloaded synchronously once it hard-expires.
     *
     * <p>No {@code refreshAheadEnabled()} check here: with refresh-ahead off, {@link #store} sets
     * {@code refreshAtNanos == expiresAtNanos}, so {@link Entry.Hit#isStale} can only turn true at
     * the instant the entry stops being live — and {@link #lookup} evicts it on that path instead
     * of reaching this method. A guard here would be unreachable, and unreachable guards read as
     * if they protect something.
     */
    private void triggerRefreshAhead(String key) {
        if (inFlight.containsKey(key) || !refreshPermits.tryAcquire()) {
            return;
        }
        refreshAheads.increment();
        try {
            maintenance.execute(() -> {
                try {
                    load(key);
                } catch (RuntimeException | Error e) {
                    LOG.log(Level.DEBUG, "Refresh-ahead failed for " + keySpace + "=" + key, e);
                } finally {
                    refreshPermits.release();
                }
            });
        } catch (RuntimeException e) {
            refreshPermits.release();   // rejected execution, e.g. during shutdown
        }
    }

    /**
     * Unwraps the {@link CompletionException} {@code join} wraps a failure in, so a coalesced
     * waiter sees exactly what the loading thread threw.
     *
     * <p>The cause is always a {@link RuntimeException} or an {@link Error}: these futures are
     * private to this class and {@link #load} is the only writer, completing them exceptionally
     * from a {@code catch (RuntimeException | Error)}. Nothing cancels them either, so there is
     * no cancellation path to handle. Previous versions carried fallbacks for a checked cause
     * and for cancellation; both were unreachable, and unreachable error handling is worse than
     * none — it cannot be exercised, so it cannot be trusted.
     */
    private static List<PartyRegistrationDetails> await(
            CompletableFuture<List<PartyRegistrationDetails>> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw (Error) cause;
        }
    }

    // ------------------------------------------------------------------ storing

    private List<PartyRegistrationDetails> store(String key, List<PartyRegistrationDetails> fetched,
                                                  boolean volatileEntry) {
        List<PartyRegistrationDetails> canonical;
        if (fetched.size() == 1) {
            canonical = List.of(pool.canonicalize(fetched.get(0)));
        } else {
            List<PartyRegistrationDetails> tmp = new ArrayList<>(fetched.size());
            for (PartyRegistrationDetails d : fetched) {
                tmp.add(pool.canonicalize(d));
            }
            canonical = List.copyOf(tmp);
        }

        long now = System.nanoTime();
        // No jitter on volatile entries: they are few, and freshness matters more than smoothing.
        long ttlNanos = volatileEntry ? config.volatileTtl().toNanos() : jitteredTtlNanos();
        long hardExpiry = now + ttlNanos;
        long refreshAt = (config.refreshAheadEnabled() && !volatileEntry)
                ? now + (long) (ttlNanos * config.refreshAheadThreshold())
                : hardExpiry;

        entries.put(storedKey(key, canonical.get(0)), new Entry.Hit(canonical, refreshAt, hardExpiry));
        maybeSweep();
        return canonical;
    }

    /**
     * Reuses the record's own pooled string as the map key where possible. The record already
     * retains it, so the key adds nothing; the fallback costs one small pooled string.
     */
    private String storedKey(String key, PartyRegistrationDetails first) {
        String fromRecord = keyOfRecord.apply(first);
        if (fromRecord != null && key.equals(keySpace.normalize(fromRecord))) {
            return fromRecord;
        }
        return pool.canonicalize(key);
    }

    private long jitteredTtlNanos() {
        long base = config.ttl().toNanos();
        double jitter = config.ttlJitter();
        if (jitter == 0) {
            return base;
        }
        return (long) (base * (1.0 + ThreadLocalRandom.current().nextDouble(-jitter, jitter)));
    }

    private PartyRegistrationUnavailableException unavailable(UnavailabilityReason reason,
                                                              String key, String referenceId) {
        return new PartyRegistrationUnavailableException(reason, keySpace.name(), key,
                keySpace + "=" + key + " cannot be served (" + reason + ")"
                        + (referenceId == null ? "" : " [ref " + referenceId + "]"),
                referenceId, null);
    }

    // ------------------------------------------------------------------ maintenance

    void invalidate(String rawValue) {
        String key = keySpace.normalize(rawValue);
        if (key != null) {
            entries.remove(key);
        }
    }

    void invalidateAll() {
        entries.clear();
        pool.clear();   // safe: interning is an optimization, never a correctness requirement
    }

    /**
     * Checks the ceiling only every {@code sweepEveryInserts} inserts, and hands the work to a
     * background thread. A {@code removeIf} across a large map is a latency spike, and it has no
     * business running on a thread that is answering a lookup.
     */
    private void maybeSweep() {
        if (config.maxEntries() <= 0
                || insertsSinceSweep.incrementAndGet() < config.sweepEveryInserts()) {
            return;
        }
        insertsSinceSweep.set(0);
        if (entries.size() <= config.maxEntries() || !sweeping.compareAndSet(false, true)) {
            return;
        }
        try {
            maintenance.execute(() -> {
                // try/finally rather than try/catch: what must hold is that the flag is released
                // so a later insert can sweep again. A throwing sweep is already surfaced by the
                // executor's uncaught-exception handling, and catching it here only to log would
                // add a branch that nothing can exercise.
                try {
                    sweep();
                } finally {
                    sweeping.set(false);
                }
            });
        } catch (RuntimeException e) {
            sweeping.set(false);
        }
    }

    /**
     * Expired entries go first; live ones are evicted only if that is not enough.
     *
     * <p>The string pool is cleared alongside a deep sweep, because after a large eviction most of
     * its contents are referenced by nothing and it would otherwise pin them. Survivors keep their
     * old instances while new entries get fresh ones, so two copies of some values coexist briefly;
     * this converges within one entry lifetime.
     */
    private void sweep() {
        long now = System.nanoTime();
        entries.entrySet().removeIf(e -> !e.getValue().isLive(now));
        int excess = entries.size() - config.maxEntries();
        if (excess > 0) {
            // Snapshot the victims, then remove them. An iterator with a countdown needs a
            // hasNext() guard for the case where a concurrent eviction empties the map mid-loop —
            // a branch that no single-threaded test can reach. `limit` expresses the same bound
            // without one.
            entries.keySet().stream().limit(excess).toList().forEach(entries::remove);
            pool.clear();
        }
        LOG.log(Level.DEBUG, "Swept {0} to {1} entries", keySpace, entries.size());
    }

    CacheStats stats() {
        return new CacheStats(keySpace.name(), hits.sum(), misses.sum(), negativeHits.sum(),
                staleHits.sum(), refreshAheads.sum(), loads.sum(), loadFailures.sum(),
                guardBlocks.sum(), blockedHits.sum(), coalesced.sum(),
                entries.size(), pool.size(), pool.hitCount());
    }
}
