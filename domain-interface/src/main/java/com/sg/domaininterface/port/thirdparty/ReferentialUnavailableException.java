package com.sg.domaininterface.port.thirdparty;

/**
 * A referential could not be reached, or answered with something unusable.
 *
 * <p>Deliberately not the same thing as an empty result. "There is no such party" and "we cannot
 * currently tell you whether there is such a party" lead to different outcomes for an invoice —
 * the first is a refusal the sender can act on, the second is an outage that should be retried
 * and should not be blamed on them. Collapsing the two into an empty list is how an outage turns
 * into a batch of wrongly-refused invoices.
 *
 * <p>{@link #isRetryable()} carries that distinction to the caller. A 5xx or a timeout is worth
 * another attempt; a 400 means the request itself is wrong and will be just as wrong next time.
 */
public class ReferentialUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String referential;
  private final boolean retryable;

  public ReferentialUnavailableException(String referential, String message, boolean retryable,
                                         Throwable cause) {
    super(message, cause);
    this.referential = referential;
    this.retryable = retryable;
  }

  /** Which referential failed, so an alert names something specific. */
  public String referential() {
    return referential;
  }

  /** Whether another attempt could plausibly succeed. */
  public boolean isRetryable() {
    return retryable;
  }
}
