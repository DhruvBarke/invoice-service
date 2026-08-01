/**
 * Alerting adapter: detects defects in referential responses, records them for correction, and emails
 * a human.
 *
 * <p>Implements {@code ResponseGuard}. This is the only type the cache module sees from here, and it
 * sees it through a domain port — remove this module from the classpath and the cache still builds
 * and runs on {@code ResponseGuard.passThrough()}.
 *
 * <p><b>The rules are not here.</b> Detection and servability live in the domain. This module
 * persists findings, decides what to email, and sends it. That separation is what allows blocking to
 * be guaranteed independent of notification.
 *
 * <p><b>Two switches that are frequently confused, and must not be.</b>
 * <ul>
 *   <li>{@code alerting.enabled=false} installs the pass-through guard: no detection, no recording,
 *       <em>no blocking</em>. A safety decision, and the only real break-glass.</li>
 *   <li>{@code alerting.email.enabled=false} silences mail only. Every defect is still detected,
 *       still recorded, still correctable, and blocking defects still block.</li>
 * </ul>
 * Muting email under pressure is safe. Disabling the guard is not. Put this distinction in the
 * runbook, because someone will reach for whichever they find first.
 *
 * <p><b>Why an email-only channel is acceptable.</b> The quarantine table is the system of record;
 * email is only the notification. Nothing is lost permanently if mail is muted, throttled, or
 * undeliverable. The one consequence is that an abandoned digest would vanish, so
 * {@code EmailAlertPublisher} logs the full body on abandonment as a last resort.
 *
 * <p><b>Notify once per defect, not once per occurrence.</b> The gate is {@code notified_at} in the
 * database, not an in-memory counter, so the guarantee survives restarts and holds across instances
 * rather than degrading to once per pod. A defect whose content changes is a new defect and notifies
 * again.
 *
 * <p><b>Corrections take effect immediately, everywhere.</b> {@code QuarantinePoller} watches
 * {@code updated_at} and evicts across the fleet. Without it, a correction would be visible only to
 * the instance that received it until every other one's cache lifetime lapsed.
 *
 * @readme.module Invoice Service — Alerting Adapter
 * @readme.order 0
 * @readme.depends invoice-service-domain — rules and ports. NOT the cache module.
 */
package com.example.invoice.service.alerting;
