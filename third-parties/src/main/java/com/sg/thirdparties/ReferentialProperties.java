package com.sg.thirdparties;

import java.util.Objects;

/**
 * Where the referentials live.
 *
 * <p>A plain record, not {@code @ConfigurationProperties}. This module has no Spring context of
 * its own and its clients are constructed with one of these by {@code bootstrap}, which is the
 * module that owns configuration. Keeping the annotation out means these clients can be built in
 * a test with three strings and no container.
 *
 * @param partyBaseUrl       base URL of the party-registration referential, no trailing slash
 * @param feeCategoryBaseUrl base URL of the fee-category referential
 * @param sgDocBaseUrl       base URL of the document store
 * @param emailBaseUrl       base URL of the mail service
 */
public record ReferentialProperties(
    String partyBaseUrl,
    String feeCategoryBaseUrl,
    String sgDocBaseUrl,
    String emailBaseUrl) {

  public ReferentialProperties {
    partyBaseUrl = normalise(partyBaseUrl, "partyBaseUrl");
    feeCategoryBaseUrl = normalise(feeCategoryBaseUrl, "feeCategoryBaseUrl");
    sgDocBaseUrl = normalise(sgDocBaseUrl, "sgDocBaseUrl");
    emailBaseUrl = normalise(emailBaseUrl, "emailBaseUrl");
  }

  /**
   * A trailing slash here and a leading one on the path produce a double slash, which some
   * gateways route differently from the single-slash form. Stripping it once at construction is
   * cheaper than every call site remembering.
   */
  private static String normalise(String url, String field) {
    Objects.requireNonNull(url, field);
    if (url.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    String trimmed = url.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }
}
