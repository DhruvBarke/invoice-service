package com.example.invoice.service.domain.einvoice;

/**
 * Splits {@code <siren>_<BUSINESS>_<FEETYPE>} into an {@link EInvoiceMarker}.
 *
 * <p>The parser is deliberately permissive: it returns a marker with as many parts filled in
 * as the input allows, and leaves the rest {@code null}. The registration service inspects the
 * marker and turns any missing part into a {@link
 * com.example.invoice.service.domain.einvoice.error.ErrorCode#FEETYPE_UNRESOLVED} error rather
 * than throwing here — that keeps the parser side-effect-free and lets the alert email carry
 * the full raw value for diagnosis.
 *
 * <p>Splitting rule: first two underscores separate the three segments. Any additional
 * underscores belong to the fee-type tail, so {@code BROKERAGE_PRINCIPAL} is preserved intact.
 * The business token is looked up against {@link Business#tryParse(String)} — an unknown
 * token yields a marker with {@code business() == null}, which the caller treats as a mapping
 * error.
 */
public final class EInvoiceMarkerParser {

  private EInvoiceMarkerParser() {}

  public static EInvoiceMarker parse(String endpointValue) {
    if (endpointValue == null || endpointValue.isBlank()) {
      return new EInvoiceMarker(null, null, null, endpointValue);
    }
    String raw = endpointValue.trim();

    int first = raw.indexOf('_');
    if (first <= 0) {
      // No underscore at all, or leading underscore — nothing to parse beyond the raw string.
      return new EInvoiceMarker(raw, null, null, endpointValue);
    }
    String siren = raw.substring(0, first);

    int second = raw.indexOf('_', first + 1);
    if (second < 0) {
      // One underscore only: everything after it is the business token.
      return new EInvoiceMarker(
          siren, Business.tryParse(raw.substring(first + 1)).orElse(null), null, endpointValue);
    }
    if (second == raw.length() - 1) {
      // A second underscore with nothing after it. The business is still readable — it sits
      // BETWEEN the two separators, so the trailing one must be excluded from the token.
      return new EInvoiceMarker(
          siren, Business.tryParse(raw.substring(first + 1, second)).orElse(null),
          null, endpointValue);
    }

    String businessToken = raw.substring(first + 1, second);
    String feeType = raw.substring(second + 1);
    Business business = Business.tryParse(businessToken).orElse(null);
    return new EInvoiceMarker(siren, business, feeType.isBlank() ? null : feeType, endpointValue);
  }
}
