package com.example.invoice.service.domain.einvoice;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Business lines that submit invoices through this service.
 *
 * <p>Each incoming e-invoice's business is derived from the endpoint marker on the accounting
 * customer party — format {@code <siren>_<BUSINESS>_<FEETYPE>}. {@link
 * EInvoiceMarkerParser} does the extraction; this enum is the closed set of known values.
 *
 * <p><b>Extending.</b> Add a new value to this enum <em>and</em> a matching entry to
 * {@code invoice.service.registration.businesses} in the application config so the rule set is
 * declared for it. A new business with no configured rule set falls back to the empty rule set
 * — the registration will succeed unless another failure fires. That is deliberate: new
 * businesses onboarding shouldn't accidentally start rejecting every invoice.
 */
public enum Business {

  /** Market activities (MARK). */
  MARK,

  /** Societe Generale Securities Services. */
  SGSS,

  /** Global Transaction Banking & Payments. */
  GTPS,

  /** Global Banking Advisory. */
  GLBA;

  /**
   * Case-insensitive lookup. Empty when the token doesn't match any known business.
   */
  public static Optional<Business> tryParse(String token) {
    if (token == null || token.isBlank()) return Optional.empty();
    String normalized = token.trim().toUpperCase(Locale.ROOT);
    return Arrays.stream(values()).filter(b -> b.name().equals(normalized)).findFirst();
  }

  public static Business parse(String token) {
    Objects.requireNonNull(token, "token");
    return tryParse(token).orElseThrow(() ->
        new IllegalArgumentException("Unknown business token: " + token));
  }
}
