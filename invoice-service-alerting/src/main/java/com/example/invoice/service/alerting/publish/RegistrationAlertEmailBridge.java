package com.example.invoice.service.alerting.publish;

import com.example.invoice.service.registration.error.MappingError;
import com.example.invoice.service.registration.port.RegistrationAlertNotifier;
import java.lang.System.Logger.Level;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Adapter that turns a {@link RegistrationAlertNotifier.RegistrationAlert} into an email sent
 * via the existing {@link AlertEmailPort}.
 *
 * <p>Deliberately <em>synchronous per invoice</em>, unlike the party-registration
 * {@link EmailAlertPublisher} (which digests over a window). The requirement is spec point 10:
 * one comprehensive alert per failed invoice covering every accumulated
 * {@link MappingError}. Digesting across invoices would drop the connection between the
 * email and the specific row an operator needs to open — and one failing e-invoice does not
 * generate the burst pattern that motivated digest aggregation on the party-registration side.
 *
 * <p>Failures inside {@link AlertEmailPort#send(EmailMessage)} are logged at WARN and
 * swallowed. The registration row is already persisted; a mail transport hiccup must never
 * turn a stored CANCELLED invoice into a caller-facing 500.
 */
public final class RegistrationAlertEmailBridge implements RegistrationAlertNotifier {

  private static final System.Logger LOG =
      System.getLogger(RegistrationAlertEmailBridge.class.getName());

  private static final DateTimeFormatter TS = DateTimeFormatter.ISO_INSTANT;

  /** Frames rendered per throwable before truncating. */
  private static final int STACK_FRAME_LIMIT = 12;

  /** How far down the {@code getCause()} chain to walk. */
  private static final int CAUSE_CHAIN_LIMIT = 5;

  private final AlertEmailPort emailPort;
  private final List<String> recipients;
  private final String subjectPrefix;

  public RegistrationAlertEmailBridge(AlertEmailPort emailPort,
                                      List<String> recipients,
                                      String subjectPrefix) {
    this.emailPort = Objects.requireNonNull(emailPort, "emailPort");
    this.recipients = List.copyOf(Objects.requireNonNull(recipients, "recipients"));
    if (this.recipients.isEmpty()) {
      throw new IllegalArgumentException("at least one recipient required");
    }
    this.subjectPrefix = subjectPrefix == null ? "[invoice-service]" : subjectPrefix;
  }

  @Override
  public void notify(RegistrationAlertNotifier.RegistrationAlert alert) {
    if (alert == null || alert.errors().isEmpty()) return;
    try {
      EmailMessage msg = new EmailMessage(recipients, subject(alert), body(alert));
      emailPort.send(msg);
    } catch (RuntimeException | Error e) {
      LOG.log(Level.WARNING,
          "failed to send registration alert email for invoice "
              + alert.invoiceReference() + " (row " + alert.invoicePayableId() + "): "
              + e.getMessage(), e);
    }
  }

  // ── Formatting ────────────────────────────────────────────────────────────

  private String subject(RegistrationAlertNotifier.RegistrationAlert a) {
    StringBuilder s = new StringBuilder(subjectPrefix.length() + 96);
    s.append(subjectPrefix).append(' ')
     .append(a.outcome().status()).append(": ")
     .append("invoice ").append(nullTo(a.invoiceReference(), "<no-id>"));
    if (a.business() != null) {
      s.append(" [").append(a.business()).append(']');
    }
    if (a.outcome().lifecycleEvent() != null) {
      s.append(" → lifecycle ").append(a.outcome().lifecycleEvent())
       .append('(').append(a.outcome().lifecycleReasonCode()).append(')');
    }
    s.append(" — ").append(a.errors().size())
     .append(a.errors().size() == 1 ? " error" : " errors");
    return s.toString();
  }

  private String body(RegistrationAlertNotifier.RegistrationAlert a) {
    StringBuilder b = new StringBuilder(1024);

    // ── Header ──
    b.append("Invoice registration failed\n")
     .append("===========================\n\n")
     .append("Row id            : ").append(nullTo(a.invoicePayableId(), "<not yet assigned>")).append('\n')
     .append("Invoice reference : ").append(nullTo(a.invoiceReference(), "<none>")).append('\n')
     .append("Business          : ").append(nullTo(a.business(), "<unresolved>")).append('\n')
     .append("Endpoint marker   : ").append(nullTo(a.marker().rawValue(), "<absent>")).append('\n')
     .append("  siren           : ").append(nullTo(a.marker().siren(), "<absent>")).append('\n')
     .append("  feetype (raw)   : ").append(nullTo(a.marker().feeType(), "<absent>")).append('\n')
     .append("Resulting status  : ").append(a.outcome().status()).append('\n')
     .append("Lifecycle event   : ")
        .append(a.outcome().lifecycleEvent() == null
            ? "none (alert-only)"
            : a.outcome().lifecycleEvent() + " with reason " + a.outcome().lifecycleReasonCode())
        .append('\n')
     .append("Comment on row    : ").append(nullTo(a.outcome().comment(), "<none>")).append('\n')
     .append("Detected at       : ").append(TS.format(a.occurredAt())).append("\n\n");

    // ── Errors ── (numbered, in insertion order — matches the persisted error_codes JSON)
    b.append("Errors captured (").append(a.errors().size()).append(")\n")
     .append("-----------------\n\n");

    int i = 1;
    for (MappingError err : a.errors()) {
      b.append(i++).append(". [").append(err.code().code()).append("] ")
       .append(err.code().name()).append('\n')
       .append("   description : ").append(err.code().description()).append('\n')
       .append("   detail      : ").append(err.detail()).append('\n')
       .append("   lifecycle   : ")
          .append(err.code().lifecycleEvent() == null
              ? "none (alert-only)"
              : err.code().lifecycleEvent() + " / " + err.code().reasonCode())
          .append('\n')
       .append("   detected at : ").append(TS.format(err.detectedAt())).append('\n');
      if (err.cause() != null) {
        b.append("   cause       : ")
         .append(err.cause().getClass().getSimpleName())
         .append(": ").append(nullTo(err.cause().getMessage(), "<no message>")).append('\n');
        b.append("   stacktrace  :\n").append(indent(stack(err.cause()), "     ")).append('\n');
      }
      b.append('\n');
    }

    // ── Footer ──
    b.append("Precedence rule (see RegistrationOutcome.decide):\n")
     .append("  REFUSED > SUSPENDED > INCOMPLETE > REGISTERED\n")
     .append("The lifecycle event above is the class of the highest-precedence error.\n")
     .append("The row is stored in t_invoice_payable; the pending lifecycle event will be\n")
     .append("picked up by the scheduler and posted to e-invoice-service.\n");

    return b.toString();
  }

  /**
   * Renders the top {@value #STACK_FRAME_LIMIT} frames plus the cause chain.
   *
   * <p>Hand-rolled rather than {@code printStackTrace}: a full trace in an email is usually
   * hundreds of frames of framework noise, and the frames that identify the defect are always
   * at the top. Truncating keeps the message readable and bounds the size of a mail that may
   * be sent once per failed invoice.
   */
  static String stack(Throwable t) {
    StringBuilder sb = new StringBuilder(512);
    Throwable current = t;
    int depth = 0;
    while (current != null && depth < CAUSE_CHAIN_LIMIT) {
      if (depth > 0) {
        sb.append("Caused by: ");
      }
      sb.append(current.getClass().getName());
      if (current.getMessage() != null) {
        sb.append(": ").append(current.getMessage());
      }
      sb.append('\n');

      StackTraceElement[] frames = current.getStackTrace();
      int shown = Math.min(frames.length, STACK_FRAME_LIMIT);
      for (int i = 0; i < shown; i++) {
        sb.append("    at ").append(frames[i]).append('\n');
      }
      if (frames.length > shown) {
        sb.append("    ... ").append(frames.length - shown).append(" more frame(s)\n");
      }
      current = current.getCause();
      depth++;
    }
    return sb.toString();
  }

  private static String indent(String s, String prefix) {
    return s.replace("\n", "\n" + prefix);
  }

  private static String nullTo(Object o, String fallback) {
    return o == null ? fallback : o.toString();
  }
}
