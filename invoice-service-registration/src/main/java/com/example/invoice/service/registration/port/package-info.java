/**
 * Secondary ports the registration pipeline depends on.
 *
 * <ul>
 *   <li>{@link ExistingInvoicePayableLookup} — duplicate check against
 *       {@code t_invoice_payable}. Impl in {@code invoice-service-app} (JDBC).</li>
 *   <li>{@link LifecycleEventPublisher} — records a pending REFUSED / SUSPENDED lifecycle
 *       event on the persisted row. Impl in {@code invoice-service-app} (writes lifecycle
 *       columns; a future scheduler drains PENDING → SENT).</li>
 *   <li>{@link RegistrationAlertNotifier} — one alert per failed invoice, covering every
 *       accumulated {@link com.example.invoice.service.registration.error.MappingError}. Impl
 *       in {@code invoice-service-alerting} (email via {@code AlertEmailPort}).</li>
 * </ul>
 *
 * <p>The persistence port itself ({@code InvoicePayableStore}) lives elsewhere (in the app
 * module today) because it deals with the whole {@code InvoicePayableModel} payload, not a
 * registration-pipeline concept. The orchestrator takes it via constructor too, but it is not
 * a "port" in the strict hexagonal sense of "declared by the domain" — the domain is party-
 * registration; invoice persistence is an application-layer concern.
 */
package com.example.invoice.service.registration.port;
