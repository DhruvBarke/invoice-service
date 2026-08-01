/**
 * Email notification.
 *
 * <p><b>Never on a lookup thread.</b> Publication merges into an in-memory map and returns; a single
 * daemon thread performs every send. A hung mail relay costs nothing on the request path.
 *
 * <p><b>Digest, not firehose.</b> Notifications are keyed by fingerprint and merged, so a defect hit
 * four thousand times becomes one line reading "x4000". Without this, the first bad record in a hot
 * path would mail-bomb the recipients and get the whole mechanism muted by whoever receives it — the
 * failure mode that makes email-based alerting useless in practice.
 *
 * <p><b>No notifying about notification failures.</b> A failed send is logged and dropped, never
 * turned into a notification: that would queue another email, fail again, and add load to an endpoint
 * that is already unhealthy. A reentrancy guard makes this structurally impossible even if a delegate
 * is misconfigured.
 *
 * <p><b>The abandonment log line is load-bearing.</b> Email is the only channel, so a batch dropped
 * after exhausted retries would otherwise vanish. The full digest body is logged at ERROR. Every
 * defect also remains in the quarantine table — that, not the log, is the real safety net.
 *
 * @readme.section Notification
 * @readme.order 80
 */
package com.example.invoice.service.alerting.publish;
