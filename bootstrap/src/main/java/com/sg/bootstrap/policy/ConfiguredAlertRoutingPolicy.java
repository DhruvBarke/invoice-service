package com.sg.bootstrap.policy;

import com.sg.bootstrap.config.RegistrationProperties;
import com.sg.bootstrap.config.RegistrationProperties.Alert;
import com.sg.bootstrap.config.RegistrationProperties.BusinessConfig;
import com.sg.bootstrap.config.RegistrationProperties.FeeCategoryConfig;
import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.port.out.AlertRoutingPolicy;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * {@link AlertRoutingPolicy} backed by {@code invoice.service.registration.*}.
 *
 * <p>Each of the three settings resolves independently, most specific first: fee category, then
 * business, then the service-wide default. Independently, because they are answers to different
 * questions — a business that only wants to override the recipient list should not have to
 * restate {@code enabled} and the subject prefix to keep them.
 *
 * <p>Fee categories match case-insensitively. They arrive from the endpoint marker as whatever
 * the sender typed, and config keyed {@code CUSTODY} should not be missed by a marker saying
 * {@code custody}.
 */
public final class ConfiguredAlertRoutingPolicy implements AlertRoutingPolicy {

  private final RegistrationProperties properties;

  public ConfiguredAlertRoutingPolicy(RegistrationProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  @Override
  public Route routeFor(Business business, String feeCategory) {
    BusinessConfig businessConfig =
        business == null ? null : properties.getBusinesses().get(business);
    FeeCategoryConfig feeConfig = feeCategoryConfig(businessConfig, feeCategory);

    Alert fee = feeConfig == null ? null : feeConfig.getAlert();
    Alert biz = businessConfig == null ? null : businessConfig.getAlert();
    Alert fallback = properties.getAlert();

    // Absent means "did not say", so it inherits. Only an explicit false switches alerting off.
    Boolean enabled = firstNonNull(
        fee == null ? null : fee.getEnabled(),
        biz == null ? null : biz.getEnabled(),
        fallback.getEnabled());

    List<String> recipients = firstNonEmpty(
        fee == null ? null : fee.getRecipients(),
        biz == null ? null : biz.getRecipients(),
        fallback.getRecipients());

    String subjectPrefix = firstNonBlank(
        fee == null ? null : fee.getSubjectPrefix(),
        biz == null ? null : biz.getSubjectPrefix(),
        fallback.getSubjectPrefix());

    // Default on. An unconfigured scope that went silent would hide failures in exactly the
    // environments nobody has finished configuring yet, which is where they matter most.
    return new Route(enabled == null || enabled, recipients,
        subjectPrefix == null ? "[invoice-service]" : subjectPrefix);
  }

  private static FeeCategoryConfig feeCategoryConfig(BusinessConfig business, String feeCategory) {
    if (business == null || feeCategory == null || feeCategory.isBlank()) {
      return null;
    }
    String wanted = feeCategory.trim().toUpperCase(Locale.ROOT);
    for (Map.Entry<String, FeeCategoryConfig> entry : business.getFeeCategories().entrySet()) {
      if (entry.getKey().trim().toUpperCase(Locale.ROOT).equals(wanted)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static Boolean firstNonNull(Boolean fee, Boolean business, Boolean fallback) {
    if (fee != null) {
      return fee;
    }
    return business != null ? business : fallback;
  }

  /**
   * An empty list counts as "did not say".
   *
   * <p>A business that declares the key and leaves it empty has not chosen recipients yet; it is
   * not asking for an alert sent to nobody. Sending to nobody would be rejected by the transport
   * as an empty To header, which reads as a mail fault rather than the config gap it is.
   *
   * <p>Three explicit parameters rather than varargs: a generic varargs array is unchecked, and
   * suppressing that with {@code @SafeVarargs} to save two characters at the one call site is a
   * poor trade.
   */
  private static List<String> firstNonEmpty(List<String> fee, List<String> business,
                                            List<String> fallback) {
    if (fee != null && !fee.isEmpty()) {
      return fee;
    }
    if (business != null && !business.isEmpty()) {
      return business;
    }
    return fallback == null ? List.of() : fallback;
  }

  private static String firstNonBlank(String fee, String business, String fallback) {
    if (fee != null && !fee.isBlank()) {
      return fee;
    }
    if (business != null && !business.isBlank()) {
      return business;
    }
    return fallback != null && !fallback.isBlank() ? fallback : null;
  }
}
