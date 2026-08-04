package com.sg.alert;

import com.sg.domaininterface.port.out.QuarantineRecord;
import com.sg.domaininterface.port.out.QuarantineStore;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Propagates corrections to every application instance.
 *
 * <p>An operator's correction reaches the database, not the JVM holding the stale cache entry. On a
 * single instance a local eviction would suffice; across a fleet it would not, and "corrections apply
 * immediately" would silently mean "on one pod immediately, on the rest within a cache lifetime".
 * Polling {@code updated_at} closes that gap without requiring a message bus.
 */
public final class QuarantinePoller implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(QuarantinePoller.class.getName());
    private static final int BATCH_LIMIT = 500;

    private final QuarantineStore store;
    private final Duration interval;
    /** Receives (keySpace, lookupKey) for every changed row, to evict the local entry. */
    private final BiConsumer<String, String> invalidator;
    private final ScheduledExecutorService scheduler;

    private volatile Instant watermark;

    public QuarantinePoller(QuarantineStore store, Duration interval,
                            BiConsumer<String, String> invalidator) {
        this.store = Objects.requireNonNull(store, "store");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.invalidator = Objects.requireNonNull(invalidator, "invalidator");
        this.watermark = Instant.now().minus(interval);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "party-quarantine-poller");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        long millis = interval.toMillis();
        scheduler.scheduleWithFixedDelay(
                // An exception escaping here would silently cancel every future poll.
                () -> {
                    try {
                        poll();
                    } catch (RuntimeException | Error e) {
                        LOG.log(Level.WARNING, "Quarantine poll failed", e);
                    }
                }, millis, millis, TimeUnit.MILLISECONDS);
    }

    /**
     * The watermark is deliberately rewound by one interval on each pass. {@code updated_at} is
     * written by the database clock while the watermark advances on this one, so a row committed
     * microseconds before the previous read would otherwise be skipped permanently. Re-processing a
     * row is harmless — it evicts an entry that is already gone.
     */
    private void poll() {
        List<QuarantineRecord> changed = store.findChangedSince(watermark, BATCH_LIMIT);
        if (changed.isEmpty()) {
            watermark = Instant.now().minus(interval);
            return;
        }
        for (QuarantineRecord row : changed) {
            invalidator.accept(row.keySpace(), row.lookupKey());
        }
        LOG.log(Level.DEBUG, "Quarantine poll evicted {0} cache entries", changed.size());

        Instant newest = changed.get(changed.size() - 1).updatedAt();
        watermark = (newest == null ? Instant.now() : newest).minus(interval);

        if (changed.size() == BATCH_LIMIT) {
            LOG.log(Level.INFO, "Quarantine poll hit the batch limit; more changes pending");
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
