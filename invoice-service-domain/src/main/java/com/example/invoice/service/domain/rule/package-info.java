/**
 * The business rules: what makes registration details usable, and which record wins when several
 * compete.
 *
 * <p><b>These were previously in the alerting adapter.</b> That was the architecture's main flaw.
 * "A record with no SIREN cannot anchor an invoice" is an invariant the business would recognise and
 * want reviewed — it is not an implementation detail of sending mail. Keeping it in the adapter had
 * three concrete costs: the invariants could not be tested without SMTP and JDBC on the classpath;
 * the servability decision was expressed as a mail severity, so a domain rule spoke in notification
 * vocabulary; and blocking without alerting was impossible to configure because one implied the
 * other.
 *
 * <p><b>Everything here is pure.</b> No I/O, no state, no clock. {@code AnomalyDetector} takes a
 * response and returns findings. That is what makes the rules cheap enough to run on every load and
 * testable with plain assertions.
 *
 * <p><b>Severity does not exist here; servability does.</b> {@code Servability} answers a single
 * question — may these details be served — and that is all the domain needs. Alerting maps it onto
 * its own behaviour for the digest. The direction of that mapping is the safety property:
 * notification concerns can never influence servability, because this module cannot see them.
 *
 * @readme.section Rules
 * @readme.order 20
 */
package com.example.invoice.service.domain.rule;
