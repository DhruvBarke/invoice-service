package com.sg.domaininterface.port.in;

import com.sg.domaininterface.model.einvoice.error.RegistrationOutcome;
import java.util.Objects;

/**
 * The registration could not be stored.
 *
 * <p>This is the one failure {@link InvoiceRegistrationService#register} raises rather than
 * returns. Every other problem — a malformed marker, an unresolvable fee category, a missing
 * attachment — comes back as a {@link RegistrationOutcome}, because the invoice was recorded and
 * there is a row to point at. Here there is no row, so returning an outcome would tell the caller
 * their invoice is stored when it is not, and nothing would ever resend it.
 *
 * <p>The outcome that <em>would</em> have been written travels on the exception, so the alert and
 * the API response can both say what was wrong with the invoice as well as that it was lost.
 */
public class RegistrationFailedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient RegistrationOutcome outcome;

  public RegistrationFailedException(String message, RegistrationOutcome outcome, Throwable cause) {
    super(message, cause);
    this.outcome = Objects.requireNonNull(outcome, "outcome");
  }

  /** What the registration had decided before the write failed. Never null. */
  public RegistrationOutcome outcome() {
    return outcome;
  }
}
