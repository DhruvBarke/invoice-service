/**
 * Driven ports: what the domain needs the outside world to provide.
 *
 * <p>Four, each with exactly one adapter today and no assumption that it stays that way.
 *
 * <p><b>{@code ReferentialGateway}</b> — where registration details come from. Deliberately narrower
 * than the referential's own interface: two methods, taking strings and returning domain records.
 * That keeps the referential's request and response DTOs confined to a single adapter class, so a
 * field rename upstream has a blast radius of one file, and lets tests supply a lambda.
 *
 * <p><b>{@code ResponseGuard}</b> — the seam through which data-quality handling attaches. This is
 * the port that makes alerting genuinely optional. It is defined here, not in the cache module,
 * because a response's servability is a domain question; the cache merely honours the verdict. Its
 * {@code passThrough()} default is a complete, honest implementation, so the cache runs with no
 * quarantine table, no database and no mail configuration at all.
 *
 * <p><b>{@code QuarantineStore}</b> — durable record of defects and their corrections. The store,
 * not the email, is the system of record: that asymmetry is what makes an email-only notification
 * channel safe, since silencing mail loses nothing permanently.
 *
 * <p><b>{@code AlertNotifier}</b> — how a human is told. Named for the role rather than the medium,
 * so the current email-only implementation is a choice rather than a constraint baked into the port.
 *
 * <p><b>Failure semantics are part of each contract</b> and are documented on the interfaces. The
 * recurring principle: an adapter's failure must degrade the feature it provides, never availability
 * of the lookup itself. A dead quarantine store means defects go unrecorded; it does not mean party
 * lookups fail.
 *
 * @readme.section Driven ports (outbound)
 * @readme.order 40
 */
package com.example.invoice.service.domain.port.out;
