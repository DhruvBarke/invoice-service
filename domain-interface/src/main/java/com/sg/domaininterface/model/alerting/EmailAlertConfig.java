package com.sg.domaininterface.model.alerting;

import com.sg.domaininterface.rule.party.Servability;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * @param digestInterval        how often pending notifications are flushed as one email. The single
 *                              most important setting: it converts a storm into one message. Longer
 *                              is usually better — the aggregation means nothing is lost, only
 *                              delayed. Dropping it to seconds reintroduces the storm the digest
 *                              exists to prevent.
 * @param immediateServability  when BLOCKING, a new blocking defect flushes at once rather than
 *                              waiting for the interval. Repeats of an already-seen fingerprint still
 *                              aggregate, so this cannot itself become a storm.
 * @param maxFingerprints       cap on distinct pending problems. Beyond this, new ones are counted
 *                              and dropped from the digest rather than growing the queue without
 *                              bound; they remain in the quarantine table regardless.
 */
public record EmailAlertConfig(
        List<String> recipients,
        String subjectPrefix,
        Duration digestInterval,
        Servability immediateServability,
        int maxFingerprints,
        int maxRetries,
        Duration retryBackoff,
        Duration shutdownFlushTimeout
) {
    public EmailAlertConfig {
        recipients = List.copyOf(Objects.requireNonNull(recipients, "recipients"));
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("at least one recipient is required");
        }
        Objects.requireNonNull(immediateServability, "immediateServability");
        Objects.requireNonNull(subjectPrefix, "subjectPrefix");
        requirePositive(digestInterval, "digestInterval");
        requirePositive(retryBackoff, "retryBackoff");
        requirePositive(shutdownFlushTimeout, "shutdownFlushTimeout");
        if (maxFingerprints <= 0) {
            throw new IllegalArgumentException("maxFingerprints must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
    }

    public static EmailAlertConfig defaults(List<String> recipients, String subjectPrefix) {
        return new EmailAlertConfig(recipients, subjectPrefix, Duration.ofMinutes(5),
                Servability.BLOCKING, 500, 3, Duration.ofSeconds(2), Duration.ofSeconds(10));
    }

    /**
     * Public because the publisher that asks this question now lives in another module.
     *
     * <p>It was package-private when the config and the publisher shared a package. The policy —
     * which severities skip the batch window — belongs with the config that declares the
     * threshold, not copied into the publisher, so widening it is the honest fix rather than
     * moving the decision to the caller.
     */
    public boolean isImmediate(Servability servability) {
        return immediateServability == servability;
    }

    private static void requirePositive(Duration d, String name) {
        if (d == null || d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
