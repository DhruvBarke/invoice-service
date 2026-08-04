package com.sg.domain.alerting;

import com.sg.domaininterface.model.alerting.EmailAlertConfig;
import com.sg.domaininterface.model.alerting.EmailMessage;
import com.sg.domaininterface.model.party.Flow;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.port.out.AlertEmailPort;
import com.sg.domaininterface.port.out.AlertNotifier;
import com.sg.domaininterface.rule.party.AnomalyType;
import com.sg.domaininterface.rule.party.Servability;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The digest publisher: aggregation by fingerprint, immediate flush for blocking defects,
 * retry-then-abandon, and the reentrancy guard that stops a failing mail path recursing.
 */
class EmailAlertPublisherTest {

  private static final PartyRegistrationDetails SAMPLE = new PartyRegistrationDetails(
      "E1", "elem", "EMN", "TP1", "tp", "TPM",
      "G1", "Acme SA", "ACME", "123456789", "12345678900012", List.of());

  private static AlertNotifier.Notification notification(
      String fingerprint, AnomalyType type, Servability servability) {
    return new AlertNotifier.Notification(type, servability, Flow.INBOUND, fingerprint,
        type.name() + " happened", Instant.now(), Map.of("keySpace", "SIREN"), List.of(SAMPLE));
  }

  private static EmailAlertConfig config(Duration digest, int maxFingerprints, int maxRetries) {
    return new EmailAlertConfig(List.of("ops@example.com"), "[party]", digest,
        Servability.BLOCKING, maxFingerprints, maxRetries,
        Duration.ofMillis(1), Duration.ofSeconds(5));
  }

  /** Collects sent messages and can be told to fail a number of times first. */
  private static final class FlakyPort implements AlertEmailPort {
    final List<EmailMessage> sent = new CopyOnWriteArrayList<>();
    final AtomicInteger attempts = new AtomicInteger();
    volatile int failFirst;
    volatile CountDownLatch arrived;

    @Override public void send(EmailMessage message) {
      attempts.incrementAndGet();
      if (attempts.get() <= failFirst) {
        throw new IllegalStateException("SMTP refused (attempt " + attempts.get() + ")");
      }
      sent.add(message);
      if (arrived != null) arrived.countDown();
    }
  }

  // ── Immediate flush ───────────────────────────────────────────────────────

  @Test
  @DisplayName("a new blocking defect flushes at once rather than waiting for the window")
  void blockingDefectFlushesImmediately() throws Exception {
    FlakyPort port = new FlakyPort();
    port.arrived = new CountDownLatch(1);

    // A long digest interval proves the send was triggered by the servability, not the timer.
    try (EmailAlertPublisher publisher =
             new EmailAlertPublisher(port, config(Duration.ofHours(1), 500, 0))) {
      publisher.notify(notification("fp-1", AnomalyType.MISSING_SIREN, Servability.BLOCKING));

      assertTrue(port.arrived.await(5, TimeUnit.SECONDS),
          "a defect that stops processing should not wait out a digest window");
      assertEquals(1, port.sent.size());
      assertTrue(port.sent.get(0).subject().contains("BLOCKING"));
      assertTrue(port.sent.get(0).body().contains("MISSING_SIREN"));
    }
  }

  @Test
  @DisplayName("a servable defect waits for the digest instead of flushing")
  void servableDefectWaits() throws Exception {
    FlakyPort port = new FlakyPort();

    try (EmailAlertPublisher publisher =
             new EmailAlertPublisher(port, config(Duration.ofHours(1), 500, 0))) {
      publisher.notify(notification("fp-1", AnomalyType.MISSING_SIRET, Servability.SERVABLE));
      Thread.sleep(150);

      assertTrue(port.sent.isEmpty(),
          "aggregating cosmetic defects is the whole point of the digest");
      assertEquals(1, publisher.stats().pendingFingerprints());
    }
  }

  // ── Aggregation ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("repeats of one defect aggregate into a single counted entry")
  void repeatsAggregate() throws Exception {
    FlakyPort port = new FlakyPort();
    port.arrived = new CountDownLatch(1);

    try (EmailAlertPublisher publisher =
             new EmailAlertPublisher(port, config(Duration.ofMillis(80), 500, 0))) {
      for (int i = 0; i < 5; i++) {
        publisher.notify(notification("fp-1", AnomalyType.MISSING_SIRET, Servability.SERVABLE));
      }

      assertTrue(port.arrived.await(5, TimeUnit.SECONDS));
      String body = port.sent.get(0).body();
      assertTrue(body.contains("x5"), "memory is bounded by distinct problems, not occurrences");
      assertTrue(port.sent.get(0).subject().contains("5 occurrences"));
      assertEquals(5, publisher.stats().received());
    }
  }

  @Test
  @DisplayName("distinct defects are listed separately, worst first")
  void distinctDefectsAreListedSeparately() throws Exception {
    FlakyPort port = new FlakyPort();
    port.arrived = new CountDownLatch(1);

    try (EmailAlertPublisher publisher =
             new EmailAlertPublisher(port, config(Duration.ofMillis(80), 500, 0))) {
      publisher.notify(notification("fp-1", AnomalyType.MISSING_SIRET, Servability.SERVABLE));
      publisher.notify(notification("fp-2", AnomalyType.MISSING_SIREN, Servability.BLOCKING));

      assertTrue(port.arrived.await(5, TimeUnit.SECONDS));
      EmailMessage message = port.sent.get(0);
      assertTrue(message.subject().contains("2 issue types"));
      assertTrue(message.body().indexOf("BLOCKING") < message.body().indexOf("SERVABLE"),
          "the reader should meet the blocking defect first");
      assertTrue(message.body().contains("keySpace: SIREN"), "context is rendered");
      assertTrue(message.body().contains("goldenBdrId=G1"), "samples are rendered");
    }
  }

  @Test
  @DisplayName("past the fingerprint ceiling extra problems are dropped from the digest and reported")
  void overflowIsReported() throws Exception {
    FlakyPort port = new FlakyPort();
    port.arrived = new CountDownLatch(1);

    try (EmailAlertPublisher publisher =
             new EmailAlertPublisher(port, config(Duration.ofMillis(120), 1, 0))) {
      publisher.notify(notification("fp-1", AnomalyType.MISSING_SIRET, Servability.SERVABLE));
      publisher.notify(notification("fp-2", AnomalyType.MISSING_SIRET, Servability.SERVABLE));
      publisher.notify(notification("fp-3", AnomalyType.MISSING_SIRET, Servability.SERVABLE));

      assertTrue(port.arrived.await(5, TimeUnit.SECONDS));
      assertTrue(port.sent.get(0).body().contains("omitted from this digest"),
          "silently dropping them would make the digest lie about what happened");
      assertTrue(port.sent.get(0).body().contains("remain in the quarantine table"));
    }
  }

  @Test
  @DisplayName("a null notification is ignored")
  void nullNotificationIgnored() throws Exception {
    FlakyPort port = new FlakyPort();
    try (EmailAlertPublisher publisher =
             new EmailAlertPublisher(port, config(Duration.ofHours(1), 500, 0))) {
      publisher.notify(null);
      assertEquals(0, publisher.stats().received());
    }
  }

  // ── Retry and abandonment ─────────────────────────────────────────────────

  @Test
  @DisplayName("a transient send failure is retried with backoff")
  void transientFailureIsRetried() throws Exception {
    FlakyPort port = new FlakyPort();
    port.failFirst = 2;
    port.arrived = new CountDownLatch(1);

    try (EmailAlertPublisher publisher =
             new EmailAlertPublisher(port, config(Duration.ofHours(1), 500, 3))) {
      publisher.notify(notification("fp-1", AnomalyType.MISSING_SIREN, Servability.BLOCKING));

      assertTrue(port.arrived.await(5, TimeUnit.SECONDS));
      assertEquals(3, port.attempts.get(), "two failures then a success");
      assertEquals(1, publisher.stats().emailsSent());
      assertEquals(2, publisher.stats().sendFailures());
    }
  }

  @Test
  @DisplayName("a dead endpoint is abandoned rather than retried forever")
  void deadEndpointIsAbandoned() throws Exception {
    FlakyPort port = new FlakyPort();
    port.failFirst = Integer.MAX_VALUE;

    try (EmailAlertPublisher publisher =
             new EmailAlertPublisher(port, config(Duration.ofHours(1), 500, 2))) {
      publisher.notify(notification("fp-1", AnomalyType.MISSING_SIREN, Servability.BLOCKING));

      long deadline = System.currentTimeMillis() + 5000;
      while (publisher.stats().batchesAbandoned() == 0 && System.currentTimeMillis() < deadline) {
        Thread.sleep(20);
      }

      assertEquals(1, publisher.stats().batchesAbandoned(),
          "retrying indefinitely would turn a dead endpoint into unbounded growth");
      assertEquals(3, port.attempts.get(), "the initial attempt plus two retries");
      assertTrue(port.sent.isEmpty());
    }
  }

  // ── Shutdown ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("closing flushes what is still pending")
  void closeFlushesPending() throws Exception {
    FlakyPort port = new FlakyPort();

    EmailAlertPublisher publisher =
        new EmailAlertPublisher(port, config(Duration.ofHours(1), 500, 0));
    publisher.notify(notification("fp-1", AnomalyType.MISSING_SIRET, Servability.SERVABLE));
    publisher.close();

    assertEquals(1, port.sent.size(),
        "a shutdown should not silently discard defects already aggregated");
  }

  @Test
  @DisplayName("closing an idle publisher sends nothing")
  void closeWithNothingPending() {
    FlakyPort port = new FlakyPort();
    new EmailAlertPublisher(port, config(Duration.ofHours(1), 500, 0)).close();
    assertTrue(port.sent.isEmpty());
  }

  // ── Reentrancy ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("a notification raised while sending is suppressed, not recursed into")
  void reentrancyIsGuarded() throws Exception {
    List<EmailMessage> sent = new CopyOnWriteArrayList<>();
    AtomicInteger depth = new AtomicInteger();
    List<EmailAlertPublisher> holder = new ArrayList<>();

    AlertEmailPort reentrant = message -> {
      sent.add(message);
      depth.incrementAndGet();
      // A mail path that itself raises a defect must not drive the publisher round again.
      holder.get(0).notify(notification("fp-loop", AnomalyType.MISSING_SIREN,
          Servability.BLOCKING));
    };

    EmailAlertPublisher publisher =
        new EmailAlertPublisher(reentrant, config(Duration.ofHours(1), 500, 0));
    holder.add(publisher);

    try {
      publisher.notify(notification("fp-1", AnomalyType.MISSING_SIREN, Servability.BLOCKING));

      long deadline = System.currentTimeMillis() + 5000;
      while (sent.isEmpty() && System.currentTimeMillis() < deadline) {
        Thread.sleep(20);
      }
      Thread.sleep(200);

      assertEquals(1, sent.size(), "the notification raised during dispatch must be dropped");
    } finally {
      publisher.close();
    }
  }

  // ── Contracts ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("both collaborators are mandatory")
  void collaboratorsMandatory() {
    FlakyPort port = new FlakyPort();
    assertThrows(NullPointerException.class,
        () -> new EmailAlertPublisher(null, config(Duration.ofHours(1), 500, 0)));
    assertThrows(NullPointerException.class, () -> new EmailAlertPublisher(port, null));
  }

  @Test
  @DisplayName("stats are readable before anything has happened")
  void statsStartAtZero() throws Exception {
    try (EmailAlertPublisher publisher =
             new EmailAlertPublisher(new FlakyPort(), config(Duration.ofHours(1), 500, 0))) {
      assertNotNull(publisher.stats());
      assertEquals(0, publisher.stats().received());
      assertEquals(0, publisher.stats().emailsSent());
      assertEquals(0, publisher.stats().pendingFingerprints());
    }
  }
}
