package com.example.invoice.service.domain.einvoice;

/**
 * Parsed form of the accounting-customer-party endpoint value on an incoming e-invoice.
 *
 * <p>The endpoint carries a routing marker in the shape {@code <siren>_<BUSINESS>_<FEETYPE>}
 * (e.g. {@code 552120222_MARK_CUSTODY}, {@code 552120222_MARK_BROKERAGE_PRINCIPAL}). This
 * record is the parsed output; see {@link EInvoiceMarkerParser} for the split logic.
 *
 * <p>Both {@link #business()} and {@link #feeType()} may be {@code null} when the input is
 * malformed — the {@link com.example.invoice.service.domain.einvoice.error.ErrorCode} taxonomy
 * carries codes for those cases so the registration service can capture and alert.
 */
public record EInvoiceMarker(String siren, Business business, String feeType, String rawValue) {

  /**
   * The marker for a document whose endpoint could not be read at all.
   *
   * <p>Every field null, but not itself null: callers reach for {@code business()} on the way to
   * choosing a rule set, and an absent marker is a routine outcome rather than an exceptional
   * one. Returning null here would put a null check in front of every one of those reads to
   * describe a case the type can express perfectly well.
   */
  public static EInvoiceMarker empty() {
    return new EInvoiceMarker(null, null, null, null);
  }
}
