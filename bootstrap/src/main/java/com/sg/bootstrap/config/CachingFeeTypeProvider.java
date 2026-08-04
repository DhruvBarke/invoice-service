package com.sg.bootstrap.config;

import com.sg.mapper.einvoice.FeeTypeProvider;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Application-side {@link FeeTypeProvider} that memoises the fee-type referential for a fixed
 * TTL.
 *
 * <p>Ported verbatim (bar the package rename) from the feetype-matcher drop's
 * {@code com.sg.app.config.CachingFeeTypeProvider}. Caching deliberately lives in the
 * application rather than the mapper module: the library should not impose an eviction policy
 * on every consumer.
 *
 * <p>Crucially, this returns the <em>same immutable map instance</em> for the whole TTL window,
 * which is what allows {@link com.sg.mapper.einvoice.FeeTypeMatcher} to reuse its
 * precomputed index instead of rebuilding it per call. A provider that returns a fresh
 * {@code HashMap} per call forces a full index rebuild every invocation.
 */
public final class CachingFeeTypeProvider implements FeeTypeProvider {

  private final Supplier<Map<String, String>> loader;
  private final long ttlNanos;

  private volatile Map<String, String> snapshot;
  private volatile long expiresAt;

  public CachingFeeTypeProvider(Supplier<Map<String, String>> loader, Duration ttl) {
    if (loader == null) {
      throw new IllegalArgumentException("loader must not be null");
    }
    if (ttl == null || ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException("ttl must be positive");
    }
    this.loader = loader;
    this.ttlNanos = ttl.toNanos();
  }

  @Override
  public Map<String, String> getFeeTypeMap() {
    Map<String, String> local = snapshot;
    if (local != null && System.nanoTime() < expiresAt) {
      return local;
    }
    synchronized (this) {
      if (snapshot == null || System.nanoTime() >= expiresAt) {
        Map<String, String> loaded = loader.get();
        // Immutable copy: safe to share across threads, and guarantees no caller can mutate
        // the map the index was built from.
        snapshot = (loaded == null) ? Map.of() : Map.copyOf(loaded);
        expiresAt = System.nanoTime() + ttlNanos;
      }
      return snapshot;
    }
  }

  /** Forces the next call to reload — wire to an admin endpoint if needed. */
  public synchronized void invalidate() {
    snapshot = null;
    expiresAt = 0L;
  }
}
