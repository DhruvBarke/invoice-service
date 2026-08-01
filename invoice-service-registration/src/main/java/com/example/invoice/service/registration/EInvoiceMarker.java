package com.example.invoice.service.registration;

/**
 * Parsed form of the accounting-customer-party endpoint value on an incoming e-invoice.
 *
 * <p>The endpoint carries a routing marker in the shape {@code <siren>_<BUSINESS>_<FEETYPE>}
 * (e.g. {@code 552120222_MARK_CUSTODY}, {@code 552120222_MARK_BROKERAGE_PRINCIPAL}). This
 * record is the parsed output; see {@link EInvoiceMarkerParser} for the split logic.
 *
 * <p>Both {@link #business()} and {@link #feeType()} may be {@code null} when the input is
 * malformed — the {@link com.example.invoice.service.registration.error.ErrorCode} taxonomy
 * carries codes for those cases so the registration service can capture and alert.
 */
public record EInvoiceMarker(String siren, Business business, String feeType, String rawValue) {}
