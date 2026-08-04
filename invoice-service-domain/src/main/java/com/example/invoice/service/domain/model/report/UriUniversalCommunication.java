package com.example.invoice.service.domain.model.report;

import lombok.*;

/**
 * Electronic address container used by both {@code Sender}
 * ({@code TG-4 / TT-11}) and {@code Issuer} ({@code TG-6 / TT-16}). Carries a
 * single CEF-network endpoint identifier.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class UriUniversalCommunication {
  /** TT-11 / TT-16 — electronic address (CEF network) up to 125 chars. */
  private String uriId;
}
