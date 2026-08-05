package com.sg.domaininterface.port.out;

import com.sg.domaininterface.model.einvoice.Business;
import java.util.List;
import java.util.Objects;

/**
 * Whether to alert about a registration, and who to tell.
 *
 * <p>Alerting used to be one recipient list and one subject prefix for the whole service. That
 * does not survive contact with more than one team: MARK's custody desk does not want SGSS's
 * brokerage failures, and a shared inbox that receives everything gets filtered into a folder
 * nobody reads — which is the same as no alerting, but harder to notice.
 *
 * <p>Resolution is most-specific-first: fee category within a business, then the business, then
 * the service-wide default. The same order the validation rules use, so one mental model covers
 * both.
 */
public interface AlertRoutingPolicy {

  /**
   * @param business    the resolved business, or {@code null} if the marker yielded none
   * @param feeCategory the resolved fee category, or {@code null}
   * @return where this alert goes. Never null — see {@link Route#silent()}.
   */
  Route routeFor(Business business, String feeCategory);

  /**
   * Where one alert goes.
   *
   * @param enabled    false when this scope has alerting switched off
   * @param recipients who to email; empty is treated as disabled
   * @param subjectPrefix prepended to the subject, so a filter can route on it
   */
  record Route(boolean enabled, List<String> recipients, String subjectPrefix) {

    public Route {
      recipients = recipients == null ? List.of() : List.copyOf(recipients);
      subjectPrefix = subjectPrefix == null ? "" : subjectPrefix;
    }

    /**
     * No alert for this scope.
     *
     * <p>A route rather than a null, so callers never have to null-check before asking
     * {@link #shouldSend()} — and so "switched off" and "no configuration at all" behave the
     * same way instead of one of them throwing.
     */
    public static Route silent() {
      return new Route(false, List.of(), "");
    }

    /**
     * True when this alert is worth sending.
     *
     * <p>An enabled route with no recipients is not: it would build the message, hand it to the
     * transport and have it rejected for an empty To header, which surfaces as a transport error
     * rather than as the configuration gap it actually is.
     */
    public boolean shouldSend() {
      return enabled && !recipients.isEmpty();
    }
  }

  /** A policy that sends everything to one place. Useful as a default and in tests. */
  static AlertRoutingPolicy fixed(List<String> recipients, String subjectPrefix) {
    Route route = new Route(true, recipients, subjectPrefix);
    return (business, feeCategory) -> route;
  }

  /** A policy that sends nothing. */
  static AlertRoutingPolicy silent() {
    Objects.requireNonNull(Route.silent());
    return (business, feeCategory) -> Route.silent();
  }
}
