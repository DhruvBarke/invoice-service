package com.example.invoice.service.alerting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.invoice.service.alerting.publish.EmailAlertConfig;
import com.example.invoice.service.alerting.publish.EmailMessage;
import com.example.invoice.service.alerting.publish.SwitchGatedNotifier;
import com.example.invoice.service.domain.model.Flow;
import com.example.invoice.service.domain.model.PartyRegistrationDetails;
import com.example.invoice.service.domain.port.out.AlertNotifier;
import com.example.invoice.service.domain.rule.AnomalyType;
import com.example.invoice.service.domain.rule.Servability;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The runtime controls over alerting — the two switches an operator reaches for under
 * pressure, and the value types around them.
 */
class AlertingControlsTest {

  private static AlertNotifier.Notification notification(
      AnomalyType type, Servability servability, Flow flow) {
    return new AlertNotifier.Notification(type, servability, flow, "fp", "message",
        Instant.EPOCH, Map.of(), List.of());
  }

  // ── AlertingSwitches ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("AlertingSwitches")
  class Switches {

    @Test
    @DisplayName("everything is emailed by default")
    void allEnabledByDefault() {
      AlertingSwitches s = AlertingSwitches.allEnabled();
      assertTrue(s.isEmailEnabled());
      assertEquals(EnumSet.allOf(AnomalyType.class), s.anomalyTypes());
      assertEquals(EnumSet.allOf(Flow.class), s.flows());
      assertEquals(Servability.SERVABLE, s.minimumServability());
      assertTrue(s.shouldEmail(AnomalyType.MISSING_SIRET, Servability.SERVABLE, Flow.INBOUND));
    }

    @Test
    @DisplayName("the master switch silences everything, whatever the finer settings say")
    void masterSwitchShortCircuits() {
      AlertingSwitches s = AlertingSwitches.emailDisabled();
      assertFalse(s.isEmailEnabled());
      assertFalse(s.shouldEmail(AnomalyType.NO_REGISTRATION_FOUND, Servability.BLOCKING,
              Flow.INBOUND),
          "muting mail must not depend on which defect arrived");
    }

    @Test
    @DisplayName("raising the minimum servability keeps only blocking defects")
    void minimumServabilityFilters() {
      AlertingSwitches s = AlertingSwitches.allEnabled();
      s.setMinimumServability(Servability.BLOCKING);

      assertTrue(s.shouldEmail(AnomalyType.MISSING_SIREN, Servability.BLOCKING, Flow.INBOUND));
      assertFalse(s.shouldEmail(AnomalyType.MISSING_SIRET, Servability.SERVABLE, Flow.INBOUND));
    }

    @Test
    @DisplayName("one defect type can be muted and unmuted without touching the rest")
    void muteAndUnmuteOneAnomaly() {
      AlertingSwitches s = AlertingSwitches.allEnabled();

      s.muteAnomaly(AnomalyType.MISSING_SIRET);
      assertFalse(s.shouldEmail(AnomalyType.MISSING_SIRET, Servability.SERVABLE, Flow.INBOUND),
          "an expected defect during a migration should be mutable on its own");
      assertTrue(s.shouldEmail(AnomalyType.MISSING_SIREN, Servability.BLOCKING, Flow.INBOUND),
          "muting one type must not silence the others");

      s.unmuteAnomaly(AnomalyType.MISSING_SIRET);
      assertTrue(s.shouldEmail(AnomalyType.MISSING_SIRET, Servability.SERVABLE, Flow.INBOUND));
    }

    @Test
    @DisplayName("the anomaly and flow sets can be replaced wholesale")
    void setsCanBeReplaced() {
      AlertingSwitches s = AlertingSwitches.allEnabled();

      s.setAnomalyTypes(List.of(AnomalyType.MISSING_SIREN));
      assertTrue(s.shouldEmail(AnomalyType.MISSING_SIREN, Servability.BLOCKING, Flow.INBOUND));
      assertFalse(s.shouldEmail(AnomalyType.MISSING_SIRET, Servability.SERVABLE, Flow.INBOUND));

      s.setFlows(List.of(Flow.OUTBOUND));
      assertFalse(s.shouldEmail(AnomalyType.MISSING_SIREN, Servability.BLOCKING, Flow.INBOUND));
      assertTrue(s.shouldEmail(AnomalyType.MISSING_SIREN, Servability.BLOCKING, Flow.OUTBOUND));
    }

    @Test
    @DisplayName("a null collection empties the set rather than throwing")
    void nullCollectionEmptiesTheSet() {
      AlertingSwitches s = AlertingSwitches.allEnabled();
      s.setAnomalyTypes(null);
      s.setFlows(null);
      assertTrue(s.anomalyTypes().isEmpty());
      assertTrue(s.flows().isEmpty());
      assertFalse(s.shouldEmail(AnomalyType.MISSING_SIREN, Servability.BLOCKING, Flow.INBOUND));
    }

    @Test
    @DisplayName("toString reports the live state, for an operations endpoint")
    void toStringReportsState() {
      String s = AlertingSwitches.allEnabled().toString();
      assertTrue(s.contains("email=true"));
      assertTrue(s.contains("minServability=SERVABLE"));
    }
  }

  // ── SwitchGatedNotifier ───────────────────────────────────────────────────

  @Nested
  @DisplayName("SwitchGatedNotifier")
  class Gate {

    private static final class Counting implements AlertNotifier {
      final List<Notification> received = new ArrayList<>();
      @Override public void notify(Notification n) { received.add(n); }
    }

    @Test
    @DisplayName("a permitted notification reaches the delegate and is counted")
    void permittedPasses() {
      Counting delegate = new Counting();
      SwitchGatedNotifier gate = new SwitchGatedNotifier(AlertingSwitches.allEnabled(), delegate);

      gate.notify(notification(AnomalyType.MISSING_SIRET, Servability.SERVABLE, Flow.INBOUND));

      assertEquals(1, delegate.received.size());
      assertEquals(1, gate.passedCount());
      assertEquals(0, gate.suppressedCount());
    }

    @Test
    @DisplayName("a suppressed notification never reaches the delegate")
    void suppressedIsWithheld() {
      Counting delegate = new Counting();
      SwitchGatedNotifier gate = new SwitchGatedNotifier(AlertingSwitches.emailDisabled(), delegate);

      gate.notify(notification(AnomalyType.MISSING_SIRET, Servability.SERVABLE, Flow.INBOUND));

      assertTrue(delegate.received.isEmpty());
      assertEquals(0, gate.passedCount());
      assertEquals(1, gate.suppressedCount(),
          "the count is what tells an operator the switch is doing something");
    }

    @Test
    @DisplayName("a null notification is ignored and counted as neither")
    void nullIsIgnored() {
      Counting delegate = new Counting();
      SwitchGatedNotifier gate = new SwitchGatedNotifier(AlertingSwitches.allEnabled(), delegate);

      gate.notify(null);

      assertTrue(delegate.received.isEmpty());
      assertEquals(0, gate.passedCount());
      assertEquals(0, gate.suppressedCount());
    }

    @Test
    @DisplayName("both collaborators are mandatory")
    void collaboratorsMandatory() {
      assertThrows(NullPointerException.class,
          () -> new SwitchGatedNotifier(null, new Counting()));
      assertThrows(NullPointerException.class,
          () -> new SwitchGatedNotifier(AlertingSwitches.allEnabled(), null));
    }
  }

  // ── EmailAlertConfig ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("EmailAlertConfig")
  class Config {

    private EmailAlertConfig defaults() {
      return EmailAlertConfig.defaults(List.of("ops@example.com"), "[party]");
    }

    @Test
    @DisplayName("the defaults digest over a window and flush blocking defects at once")
    void defaultsAreSensible() {
      EmailAlertConfig c = defaults();
      assertEquals(Duration.ofMinutes(5), c.digestInterval());
      assertEquals(Servability.BLOCKING, c.immediateServability());
      assertEquals(500, c.maxFingerprints());
      assertEquals(3, c.maxRetries());
    }

    @Test
    @DisplayName("at least one recipient is required")
    void recipientsRequired() {
      assertThrows(NullPointerException.class,
          () -> EmailAlertConfig.defaults(null, "[p]"));
      assertThrows(IllegalArgumentException.class,
          () -> EmailAlertConfig.defaults(List.of(), "[p]"));
    }

    @Test
    @DisplayName("the mandatory fields are demanded by name")
    void mandatoryFields() {
      List<String> to = List.of("ops@example.com");
      assertThrows(NullPointerException.class, () -> new EmailAlertConfig(
          to, null, Duration.ofMinutes(5), Servability.BLOCKING, 500, 3,
          Duration.ofSeconds(2), Duration.ofSeconds(10)));
      assertThrows(NullPointerException.class, () -> new EmailAlertConfig(
          to, "[p]", Duration.ofMinutes(5), null, 500, 3,
          Duration.ofSeconds(2), Duration.ofSeconds(10)));
    }

    @Test
    @DisplayName("every duration must be positive")
    void durationsPositive() {
      List<String> to = List.of("ops@example.com");
      assertThrows(IllegalArgumentException.class, () -> new EmailAlertConfig(
          to, "[p]", null, Servability.BLOCKING, 500, 3,
          Duration.ofSeconds(2), Duration.ofSeconds(10)));
      assertThrows(IllegalArgumentException.class, () -> new EmailAlertConfig(
          to, "[p]", Duration.ZERO, Servability.BLOCKING, 500, 3,
          Duration.ofSeconds(2), Duration.ofSeconds(10)));
      assertThrows(IllegalArgumentException.class, () -> new EmailAlertConfig(
          to, "[p]", Duration.ofMinutes(5), Servability.BLOCKING, 500, 3,
          Duration.ofSeconds(-1), Duration.ofSeconds(10)));
      assertThrows(IllegalArgumentException.class, () -> new EmailAlertConfig(
          to, "[p]", Duration.ofMinutes(5), Servability.BLOCKING, 500, 3,
          Duration.ofSeconds(2), null));
    }

    @Test
    @DisplayName("the aggregation ceiling must be positive and retries non-negative")
    void countersBounded() {
      List<String> to = List.of("ops@example.com");
      assertThrows(IllegalArgumentException.class, () -> new EmailAlertConfig(
          to, "[p]", Duration.ofMinutes(5), Servability.BLOCKING, 0, 3,
          Duration.ofSeconds(2), Duration.ofSeconds(10)));
      assertThrows(IllegalArgumentException.class, () -> new EmailAlertConfig(
          to, "[p]", Duration.ofMinutes(5), Servability.BLOCKING, 500, -1,
          Duration.ofSeconds(2), Duration.ofSeconds(10)));

      // Zero retries is legitimate: try once, then abandon.
      assertEquals(0, new EmailAlertConfig(to, "[p]", Duration.ofMinutes(5),
          Servability.BLOCKING, 500, 0, Duration.ofSeconds(2),
          Duration.ofSeconds(10)).maxRetries());
    }
  }

  // ── EmailMessage ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("EmailMessage")
  class Message {

    @Test
    @DisplayName("recipients are copied and the content is mandatory")
    void contract() {
      List<String> to = new ArrayList<>(List.of("a@x.com"));
      EmailMessage m = new EmailMessage(to, "subject", "body");
      to.clear();
      assertEquals(1, m.to().size(), "the message keeps its own copy");
      assertEquals("subject", m.subject());
      assertEquals("body", m.body());

      assertThrows(NullPointerException.class, () -> new EmailMessage(null, "s", "b"));
      assertThrows(NullPointerException.class, () -> new EmailMessage(List.of("a@x.com"), null, "b"));
      assertThrows(NullPointerException.class, () -> new EmailMessage(List.of("a@x.com"), "s", null));
      assertThrows(IllegalArgumentException.class, () -> new EmailMessage(List.of(), "s", "b"));
    }
  }

  // ── The domain-side no-op ─────────────────────────────────────────────────

  @Test
  @DisplayName("the dispatch exception carries its cause")
  void dispatchExceptionCarriesCause() {
    Exception cause = new IllegalStateException("connection refused");
    var e = new com.example.invoice.service.alerting.publish.AlertEmailPort
        .EmailDispatchException("send failed", cause);
    assertSame(cause, e.getCause());
    assertEquals("send failed", e.getMessage());
  }

  @Test
  @DisplayName("a sample-carrying notification survives the round trip through the gate")
  void notificationWithSamplesPasses() {
    PartyRegistrationDetails sample = new PartyRegistrationDetails(
        "E1", "elem", "EMN", "TP1", "tp", "TPM",
        "G1", "Acme SA", "ACME", "123456789", "12345678900012", List.of());

    List<AlertNotifier.Notification> received = new ArrayList<>();
    SwitchGatedNotifier gate =
        new SwitchGatedNotifier(AlertingSwitches.allEnabled(), received::add);

    gate.notify(new AlertNotifier.Notification(AnomalyType.MISSING_SIRET, Servability.SERVABLE,
        Flow.INBOUND, "fp", "message", Instant.EPOCH,
        Map.of("key", "value"), List.of(sample)));

    assertEquals(1, received.size());
    assertEquals("value", received.get(0).context().get("key"));
    assertEquals(1, received.get(0).samples().size());
  }
}
