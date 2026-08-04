package com.sg.alert;

import com.sg.domaininterface.model.alerting.EmailAlertConfig;
import com.sg.domaininterface.model.alerting.EmailMessage;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.port.out.AlertEmailPort;
import com.sg.domaininterface.port.out.AlertNotifier;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Sends notifications by email, aggregated into periodic digests.
 *
 * <p>See the package documentation for the design rationale: off-thread dispatch, fingerprint
 * aggregation, the reentrancy guard, and why the abandonment log line matters.
 */
public final class EmailAlertPublisher implements AlertNotifier, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(EmailAlertPublisher.class.getName());

    /** Set while this thread is inside a send; suppresses any notification raised beneath it. */
    private static final ThreadLocal<Boolean> IN_DISPATCH = ThreadLocal.withInitial(() -> false);

    private final AlertEmailPort emailPort;
    private final EmailAlertConfig config;
    private final ScheduledExecutorService dispatcher;
    private final ConcurrentMap<String, Aggregate> pending = new ConcurrentHashMap<>();

    private final LongAdder received = new LongAdder();
    private final LongAdder droppedOverflow = new LongAdder();
    private final LongAdder emailsSent = new LongAdder();
    private final LongAdder sendFailures = new LongAdder();
    private final LongAdder batchesAbandoned = new LongAdder();

    public EmailAlertPublisher(AlertEmailPort emailPort, EmailAlertConfig config) {
        this.emailPort = Objects.requireNonNull(emailPort, "emailPort");
        this.config = Objects.requireNonNull(config, "config");
        this.dispatcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "party-alert-email-dispatcher");
            t.setDaemon(true);
            return t;
        });

        long millis = config.digestInterval().toMillis();
        dispatcher.scheduleWithFixedDelay(
                // An exception escaping here would silently cancel every future flush.
                () -> {
                    try {
                        flush();
                    } catch (RuntimeException | Error e) {
                        LOG.log(Level.WARNING, "Digest flush failed", e);
                    }
                }, millis, millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void notify(Notification notification) {
        if (notification == null || IN_DISPATCH.get()) {
            return;   // reentrancy guard
        }
        received.increment();

        boolean[] isNew = {false};
        Aggregate merged = pending.compute(notification.fingerprint(), (key, existing) -> {
            if (existing == null) {
                if (pending.size() >= config.maxFingerprints()) {
                    return null;
                }
                isNew[0] = true;
                return new Aggregate(notification);
            }
            existing.merge(notification);
            return existing;
        });

        if (merged == null) {
            // Dropped from the digest, not lost: the quarantine row exists regardless. An unbounded
            // aggregation map is a slow leak, and past a few hundred distinct problems a digest has
            // stopped being readable anyway.
            droppedOverflow.increment();
            return;
        }

        // Only a NEW fingerprint can trigger an early flush, so one hot defect cannot bypass the
        // digest interval.
        if (isNew[0] && config.isImmediate(notification.servability())) {
            try {
                dispatcher.execute(() -> {
                    try {
                        flush();
                    } catch (RuntimeException | Error e) {
                        LOG.log(Level.WARNING, "Immediate flush failed", e);
                    }
                });
            } catch (RuntimeException e) {
                LOG.log(Level.DEBUG, "Dispatcher rejected immediate flush; digest will pick it up");
            }
        }
    }

    /** Drains the aggregation map and sends one email. Runs only on the dispatcher thread. */
    private void flush() {
        if (pending.isEmpty()) {
            return;
        }
        // Drain by removal, so notifications arriving during composition land in the next digest
        // rather than being lost or duplicated.
        List<Aggregate> batch = new ArrayList<>(pending.size());
        for (String fingerprint : List.copyOf(pending.keySet())) {
            Aggregate a = pending.remove(fingerprint);
            if (a != null) {
                batch.add(a);
            }
        }
        if (batch.isEmpty()) {
            return;
        }

        batch.sort(Comparator.comparing((Aggregate a) -> a.first.servability()).reversed()
                .thenComparing(a -> -a.count.get()));

        EmailMessage message = new EmailMessage(
                config.recipients(), buildSubject(batch), buildBody(batch));

        IN_DISPATCH.set(true);
        try {
            sendWithRetry(message, batch);
        } finally {
            IN_DISPATCH.set(false);
        }
    }

    private void sendWithRetry(EmailMessage message, List<Aggregate> batch) {
        Duration backoff = config.retryBackoff();
        for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
            try {
                emailPort.send(message);
                emailsSent.increment();
                return;
            } catch (RuntimeException | Error e) {
                sendFailures.increment();
                if (attempt == config.maxRetries()) {
                    // Abandoned rather than requeued: retrying indefinitely would let a dead endpoint
                    // convert into unbounded growth, and the content is stale by then.
                    batchesAbandoned.increment();
                    LOG.log(Level.ERROR, "Abandoning alert email after " + (attempt + 1)
                            + " attempts; " + batch.size() + " aggregated notification(s) preserved "
                            + "here, and all remain in the quarantine table:\n" + message.body(), e);
                    return;
                }
                sleep(backoff);
                backoff = backoff.multipliedBy(2);
            }
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String buildSubject(List<Aggregate> batch) {
        long total = batch.stream().mapToLong(a -> a.count.get()).sum();
        Aggregate worst = batch.get(0);
        String headline = batch.size() == 1 ? worst.first.type().name()
                : batch.size() + " issue types";
        return config.subjectPrefix() + " " + worst.first.servability() + ": " + headline
                + " (" + total + " occurrence" + (total == 1 ? "" : "s") + ")";
    }

    private String buildBody(List<Aggregate> batch) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("Party registration referential - data quality digest\n")
          .append("Window: ").append(config.digestInterval()).append('\n')
          .append("Generated: ").append(Instant.now()).append("\n\n")
          .append("Every item below is recorded in party_registration_quarantine and can be "
                  + "corrected there.\n\n");

        long overflow = droppedOverflow.sumThenReset();
        if (overflow > 0) {
            sb.append("!! ").append(overflow).append(" item(s) omitted from this digest: more than ")
              .append(config.maxFingerprints())
              .append(" distinct problems were pending. They remain in the quarantine table.\n\n");
        }

        for (Aggregate a : batch) {
            AlertNotifier.Notification first = a.first;
            sb.append("-- ").append(first.servability()).append(' ').append(first.flow())
              .append('/').append(first.type()).append("  x").append(a.count.get()).append('\n')
              .append(first.message()).append('\n')
              .append("   first: ").append(first.occurredAt())
              .append("   last: ").append(Instant.ofEpochMilli(a.lastEpochMillis.get())).append('\n');

            for (Map.Entry<String, String> e : first.context().entrySet()) {
                sb.append("   ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
            for (PartyRegistrationDetails s : first.samples()) {
                sb.append("   sample: goldenBdrId=").append(s.goldenBdrId())
                  .append(" elemBdrId=").append(s.elemBdrId())
                  .append(" siren=").append(s.siren())
                  .append(" siret=").append(s.siret()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public EmailAlertStats stats() {
        return new EmailAlertStats(received.sum(), pending.size(), emailsSent.sum(),
                sendFailures.sum(), batchesAbandoned.sum());
    }

    /** Flushes what is pending, within the configured timeout, then stops the dispatcher. */
    @Override
    public void close() {
        try {
            dispatcher.submit(() -> {
                try {
                    flush();
                } catch (RuntimeException | Error e) {
                    LOG.log(Level.WARNING, "Shutdown flush failed", e);
                }
            }).get(config.shutdownFlushTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Shutdown flush did not complete in time", e);
        } finally {
            dispatcher.shutdownNow();
        }
    }

    /**
     * One fingerprint's running total. The first notification is retained for its message, context
     * and samples; later occurrences bump only the counter and timestamp, so memory is bounded by
     * distinct problems rather than by occurrences.
     */
    private static final class Aggregate {
        final AlertNotifier.Notification first;
        final AtomicLong count = new AtomicLong(1);
        final AtomicLong lastEpochMillis;

        Aggregate(AlertNotifier.Notification first) {
            this.first = first;
            this.lastEpochMillis = new AtomicLong(first.occurredAt().toEpochMilli());
        }

        void merge(AlertNotifier.Notification later) {
            count.incrementAndGet();
            lastEpochMillis.set(later.occurredAt().toEpochMilli());
        }
    }

    public record EmailAlertStats(long received, int pendingFingerprints, long emailsSent,
                                  long sendFailures, long batchesAbandoned) { }
}
