package com.sg.mapper.einvoice;

/**
 * Thrown when a mapping step cannot complete — e.g. a referenced attachment id cannot be
 * resolved, or a required currency cannot be resolved. Wraps the underlying cause so callers can
 * decide whether to retry or surface to the user.
 */
public class EInvoiceMappingException extends RuntimeException {
  /** Pinned so a rolling deployment cannot make an in-flight instance unreadable. */
  private static final long serialVersionUID = 1L;


  public EInvoiceMappingException(String message) {
    super(message);
  }

  public EInvoiceMappingException(String message, Throwable cause) {
    super(message, cause);
  }
}
