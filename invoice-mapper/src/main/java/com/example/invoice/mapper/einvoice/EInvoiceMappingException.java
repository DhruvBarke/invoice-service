package com.example.invoice.mapper.einvoice;

/**
 * Thrown when a mapping step cannot complete — e.g. a referenced attachment id cannot be
 * resolved, or a required currency cannot be resolved. Wraps the underlying cause so callers can
 * decide whether to retry or surface to the user.
 */
public class EInvoiceMappingException extends RuntimeException {

  public EInvoiceMappingException(String message) {
    super(message);
  }

  public EInvoiceMappingException(String message, Throwable cause) {
    super(message, cause);
  }
}
