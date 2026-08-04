package com.sg.alert;

import com.sg.domaininterface.model.alerting.EmailMessage;
import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.model.einvoice.EInvoiceMarker;
import com.sg.domaininterface.model.einvoice.error.ErrorCode;
import com.sg.domaininterface.model.einvoice.error.MappingError;
import com.sg.domaininterface.model.einvoice.error.RegistrationOutcome;
import com.sg.domaininterface.port.out.RegistrationAlertNotifier.RegistrationAlert;
import com.sg.domaininterface.port.out.AlertEmailPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The alert an operator actually reads. Assertions are on content rather than formatting: the
 * requirement is that one email covers every captured failure and says what to do about it.
 */
class RegistrationAlertEmailBridgeTest {

  /** Captures what would have been sent. */
  private static final class CapturingPort implements AlertEmailPort {
    final List<EmailMessage> sent = new ArrayList<>();
    @Override public void send(EmailMessage message) { sent.add(message); }
  }

  private static final EInvoiceMarker MARKER = new EInvoiceMarker(
      "552120222", Business.MARK, "CUSTODY", "552120222_MARK_CUSTODY");

  private static final UUID ROW_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

  private static RegistrationAlert alert(RegistrationOutcome outcome) {
    return new RegistrationAlert(ROW_ID, "CUS0226368", Business.MARK, MARKER, outcome, Instant.EPOCH);
  }

  private static CapturingPort send(RegistrationOutcome outcome) {
    CapturingPort port = new CapturingPort();
    new RegistrationAlertEmailBridge(port, List.of("ops@example.com"), "[invoice-service]")
        .notify(alert(outcome));
    return port;
  }

  // ── Delivery decisions ────────────────────────────────────────────────────

  @Nested
  @DisplayName("when an email is sent at all")
  class Delivery {

    @Test
    @DisplayName("a clean outcome raises nothing")
    void cleanOutcomeSendsNothing() {
      assertTrue(send(RegistrationOutcome.decide(List.of())).sent.isEmpty(),
          "a successful registration is not news");
    }

    @Test
    @DisplayName("a null alert is ignored rather than throwing")
    void nullAlertIgnored() {
      CapturingPort port = new CapturingPort();
      new RegistrationAlertEmailBridge(port, List.of("ops@example.com"), null).notify(null);
      assertTrue(port.sent.isEmpty());
    }

    @Test
    @DisplayName("exactly one email per failed invoice, however many errors it carries")
    void oneEmailPerFailedInvoice() {
      CapturingPort port = send(RegistrationOutcome.decide(List.of(
          MappingError.of(ErrorCode.MISSING_ATTACHMENT, "no file"),
          MappingError.of(ErrorCode.DUPLICATE_INVOICE, "invoice already exists"),
          MappingError.of(ErrorCode.EMPTY_LINE_ITEMS, "no lines"))));
      assertEquals(1, port.sent.size());
    }

    @Test
    @DisplayName("a mail transport failure is swallowed — the row is already durable")
    void transportFailureIsSwallowed() {
      AlertEmailPort exploding = message -> {
        throw new IllegalStateException("SMTP refused the connection");
      };
      RegistrationAlertEmailBridge bridge =
          new RegistrationAlertEmailBridge(exploding, List.of("ops@example.com"), null);

      bridge.notify(alert(RegistrationOutcome.decide(
          List.of(MappingError.of(ErrorCode.DUPLICATE_INVOICE, "dup")))));
      // Reaching here without an exception is the assertion.
    }

    @Test
    @DisplayName("at least one recipient is required")
    void recipientsAreRequired() {
      CapturingPort port = new CapturingPort();
      assertThrows(NullPointerException.class,
          () -> new RegistrationAlertEmailBridge(null, List.of("a@b.c"), null));
      assertThrows(NullPointerException.class,
          () -> new RegistrationAlertEmailBridge(port, null, null));
      assertThrows(IllegalArgumentException.class,
          () -> new RegistrationAlertEmailBridge(port, List.of(), null),
          "an alert with nowhere to go is a configuration error, not a silent no-op");
    }
  }

  // ── Subject ───────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("subject line")
  class Subject {

    @Test
    @DisplayName("carries status, invoice, business, lifecycle and a count")
    void subjectIsScannable() {
      String subject = send(RegistrationOutcome.decide(List.of(
          MappingError.of(ErrorCode.DUPLICATE_INVOICE, "invoice already exists"))))
          .sent.get(0).subject();

      assertTrue(subject.startsWith("[invoice-service]"));
      assertTrue(subject.contains("CANCELLED"));
      assertTrue(subject.contains("CUS0226368"));
      assertTrue(subject.contains("MARK"));
      assertTrue(subject.contains("REFUSED"));
      assertTrue(subject.contains("DOUBLON"));
      assertTrue(subject.contains("1 error"), "singular when there is one");
    }

    @Test
    @DisplayName("pluralises the error count")
    void pluralisesCount() {
      assertTrue(send(RegistrationOutcome.decide(List.of(
          MappingError.of(ErrorCode.MISSING_ATTACHMENT, "a"),
          MappingError.of(ErrorCode.EMPTY_LINE_ITEMS, "b"))))
          .sent.get(0).subject().contains("2 errors"));
    }

    @Test
    @DisplayName("an alert-only outcome names no lifecycle event")
    void alertOnlyOutcomeHasNoLifecycleInSubject() {
      String subject = send(RegistrationOutcome.decide(List.of(
          MappingError.of(ErrorCode.EMPTY_LINE_ITEMS, "no lines"))))
          .sent.get(0).subject();

      assertTrue(subject.contains("INCOMPLETE"));
      assertFalse(subject.contains("lifecycle"),
          "there is no event to mention, so mentioning one would mislead");
    }

    @Test
    @DisplayName("the prefix defaults when none is configured")
    void subjectPrefixDefaults() {
      CapturingPort port = new CapturingPort();
      new RegistrationAlertEmailBridge(port, List.of("ops@example.com"), null)
          .notify(alert(RegistrationOutcome.decide(
              List.of(MappingError.of(ErrorCode.DUPLICATE_INVOICE, "dup")))));
      assertTrue(port.sent.get(0).subject().startsWith("[invoice-service]"));
    }

    @Test
    @DisplayName("an unidentifiable invoice still produces a usable subject")
    void unidentifiableInvoice() {
      CapturingPort port = new CapturingPort();
      EInvoiceMarker blank = new EInvoiceMarker(null, null, null, null);
      new RegistrationAlertEmailBridge(port, List.of("ops@example.com"), null)
          .notify(new RegistrationAlert(null, null, null, blank,
              RegistrationOutcome.decide(List.of(
                  MappingError.of(ErrorCode.MARKER_MALFORMED, "no endpoint"))),
              Instant.EPOCH));

      assertTrue(port.sent.get(0).subject().contains("<no-id>"),
          "a missing reference must read as missing, not as the string 'null'");
    }
  }

  // ── Body ──────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("body")
  class Body {

    @Test
    @DisplayName("the header states the row, the marker and what happened to the invoice")
    void headerCarriesTheContext() {
      String body = send(RegistrationOutcome.decide(List.of(
          MappingError.of(ErrorCode.DUPLICATE_INVOICE, "invoice already exists"))))
          .sent.get(0).body();

      assertTrue(body.contains("Row id            : " + ROW_ID), "ops opens the row by id");
      assertTrue(body.contains("CUS0226368"));
      assertTrue(body.contains("552120222_MARK_CUSTODY"));
      assertTrue(body.contains("552120222"));
      assertTrue(body.contains("CUSTODY"));
      assertTrue(body.contains("CANCELLED"));
      assertTrue(body.contains("REFUSED with reason DOUBLON"));
      assertTrue(body.contains("invoice already exists"));
    }

    @Test
    @DisplayName("every captured error is listed, numbered, with its code and remedy class")
    void everyErrorIsListed() {
      String body = send(RegistrationOutcome.decide(List.of(
          MappingError.of(ErrorCode.MISSING_ATTACHMENT, "no file supplied"),
          MappingError.of(ErrorCode.DUPLICATE_INVOICE, "invoice already exists"),
          MappingError.of(ErrorCode.EMPTY_LINE_ITEMS, "no lines"))))
          .sent.get(0).body();

      assertTrue(body.contains("Errors captured (3)"));
      assertTrue(body.contains("1. [ATT-001]"));
      assertTrue(body.contains("2. [DUP-001]"));
      assertTrue(body.contains("3. [LIN-001]"));
      assertTrue(body.contains("MISSING_ATTACHMENT"));
      assertTrue(body.contains("no file supplied"));
      assertTrue(body.contains("SUSPENDED / JUSTIF_ABS"));
      assertTrue(body.contains("none (alert-only)"),
          "EMPTY_LINE_ITEMS fires no event, and the reader must be able to tell");
    }

    @Test
    @DisplayName("an exception cause is rendered with a truncated stack")
    void causeIsRendered() {
      RuntimeException cause = new IllegalStateException("referential timed out");
      String body = send(RegistrationOutcome.decide(List.of(
          MappingError.of(ErrorCode.PARTY_LOOKUP_FAILED, "lookup failed", cause))))
          .sent.get(0).body();

      assertTrue(body.contains("IllegalStateException"));
      assertTrue(body.contains("referential timed out"));
      assertTrue(body.contains("stacktrace"));
      assertTrue(body.contains("    at "), "frames are indented under the error");
    }

    @Test
    @DisplayName("a cause chain is walked and labelled")
    void causeChainIsWalked() {
      Exception root = new IllegalArgumentException("socket closed");
      Exception wrapper = new IllegalStateException("referential unreachable", root);
      String body = send(RegistrationOutcome.decide(List.of(
          MappingError.of(ErrorCode.PARTY_LOOKUP_FAILED, "lookup failed", wrapper))))
          .sent.get(0).body();

      assertTrue(body.contains("referential unreachable"));
      assertTrue(body.contains("Caused by: "));
      assertTrue(body.contains("socket closed"));
    }

    @Test
    @DisplayName("a cause with no message still renders")
    void causeWithoutMessage() {
      String body = send(RegistrationOutcome.decide(List.of(
          MappingError.of(ErrorCode.MAPPING_ERROR, "broke", new IllegalStateException()))))
          .sent.get(0).body();
      assertTrue(body.contains("<no message>"));
    }

    @Test
    @DisplayName("a long stack is truncated with a count of what was dropped")
    void longStackIsTruncated() {
      RuntimeException deep = deepStack(40);
      String body = send(RegistrationOutcome.decide(List.of(
          MappingError.of(ErrorCode.MAPPING_ERROR, "deep", deep))))
          .sent.get(0).body();

      assertTrue(body.contains("more frame(s)"),
          "an email is not the place for two hundred frames of framework noise");
    }

    @Test
    @DisplayName("an error with no cause renders no stack section")
    void noCauseNoStack() {
      String body = send(RegistrationOutcome.decide(List.of(
          MappingError.of(ErrorCode.DUPLICATE_INVOICE, "dup")))).sent.get(0).body();
      assertFalse(body.contains("stacktrace"));
    }

    @Test
    @DisplayName("the footer states the precedence rule and where the row lives")
    void footerExplainsWhatHappensNext() {
      String body = send(RegistrationOutcome.decide(List.of(
          MappingError.of(ErrorCode.DUPLICATE_INVOICE, "dup")))).sent.get(0).body();

      assertTrue(body.contains("REFUSED > SUSPENDED > INCOMPLETE > REGISTERED"));
      assertTrue(body.contains("t_invoice_payable"));
      assertTrue(body.contains("scheduler"));
    }

    @Test
    @DisplayName("absent optional fields read as absent, never as 'null'")
    void absentFieldsAreLabelled() {
      CapturingPort port = new CapturingPort();
      EInvoiceMarker blank = new EInvoiceMarker(null, null, null, null);
      new RegistrationAlertEmailBridge(port, List.of("ops@example.com"), null)
          .notify(new RegistrationAlert(null, null, null, blank,
              RegistrationOutcome.decide(List.of(
                  MappingError.of(ErrorCode.MARKER_MALFORMED, "no endpoint"))),
              Instant.EPOCH));

      String body = port.sent.get(0).body();
      assertTrue(body.contains("<not yet assigned>"));
      assertTrue(body.contains("<none>"));
      assertTrue(body.contains("<unresolved>"));
      assertTrue(body.contains("<absent>"));
      assertFalse(body.contains(": null"), "an operator should never read the word null");
    }

    @Test
    @DisplayName("the configured recipients are the ones addressed")
    void recipientsAreUsed() {
      CapturingPort port = new CapturingPort();
      new RegistrationAlertEmailBridge(port, List.of("a@x.com", "b@x.com"), null)
          .notify(alert(RegistrationOutcome.decide(
              List.of(MappingError.of(ErrorCode.DUPLICATE_INVOICE, "dup")))));
      assertEquals(List.of("a@x.com", "b@x.com"), port.sent.get(0).to());
    }
  }

  @Nested
  @DisplayName("stack rendering limits")
  class StackLimits {

    @Test
    @DisplayName("a short stack is rendered whole, with no truncation notice")
    void shortStackIsNotTruncated() {
      RuntimeException shallow = new IllegalStateException("shallow");
      shallow.setStackTrace(new StackTraceElement[] {
          new StackTraceElement("com.example.Thing", "doIt", "Thing.java", 10),
          new StackTraceElement("com.example.Caller", "call", "Caller.java", 20)});

      String body = send(RegistrationOutcome.decide(List.of(
          MappingError.of(ErrorCode.MAPPING_ERROR, "shallow", shallow)))).sent.get(0).body();

      assertTrue(body.contains("Thing.java"));
      assertFalse(body.contains("more frame(s)"),
          "nothing was dropped, so claiming otherwise would be noise");
    }

    @Test
    @DisplayName("a cause chain longer than the limit stops rather than unwinding forever")
    void causeChainIsBounded() {
      // Six links, one more than the bridge walks. A cycle-free but very deep chain would
      // otherwise turn one alert into an unbounded email.
      RuntimeException deepest = new IllegalStateException("link-6");
      RuntimeException chain = deepest;
      for (int i = 5; i >= 1; i--) {
        chain = new IllegalStateException("link-" + i, chain);
      }

      String body = send(RegistrationOutcome.decide(List.of(
          MappingError.of(ErrorCode.MAPPING_ERROR, "deep chain", chain)))).sent.get(0).body();

      assertTrue(body.contains("link-1"));
      assertTrue(body.contains("link-5"), "the walk covers the configured depth");
      assertFalse(body.contains("link-6"), "and stops there rather than unwinding forever");
    }
  }

  /** Builds a throwable with a stack deeper than the bridge's render limit. */
  private static RuntimeException deepStack(int depth) {
    if (depth == 0) {
      return new IllegalStateException("bottom");
    }
    return deepStack(depth - 1);
  }
}
