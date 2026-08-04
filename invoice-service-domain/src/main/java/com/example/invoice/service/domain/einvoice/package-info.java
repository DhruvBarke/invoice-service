/**
 * E-invoice → {@code InvoicePayable} registration pipeline.
 *
 * <p>Orchestrates: parse the receiver endpoint marker, run the mapping stack, apply a
 * business-scoped set of {@link com.example.invoice.service.domain.einvoice.rule.ValidationRule}s,
 * decide the resulting {@code InvoiceStatus} + lifecycle event (REFUSED / SUSPENDED / none),
 * and hand off to persistence + alerting via three secondary ports.
 *
 * <p><b>Scope: e-invoice source only.</b> Registrations from other sources (manual entry,
 * SGAI, spreadsheet imports) bypass this pipeline entirely. The controller wires the pipeline
 * only under the {@code POST /invoices/einvoice} route.
 *
 * <p><b>Precedence.</b> {@code REFUSED} lifecycle events take precedence over
 * {@code SUSPENDED} when a single registration accumulates errors of both classes. Errors that
 * map to no lifecycle event (e.g. {@code EMPTY_LINE_ITEMS} → INCOMPLETE status) are alert-only.
 *
 * <p><b>Extensibility knobs</b> (each documented on its own type):
 * <ul>
 *   <li>{@link com.example.invoice.service.domain.einvoice.Business} — add a new enum value.</li>
 *   <li>{@link com.example.invoice.service.domain.einvoice.rule.ValidationRule} — implement,
 *       register in {@link com.example.invoice.service.domain.einvoice.rule.ValidationRegistry}.</li>
 *   <li>{@link com.example.invoice.service.domain.einvoice.error.ErrorCode} — add a value with its
 *       refusal + suspension mapping.</li>
 *   <li>Per-business enable/disable via
 *       {@code invoice.service.registration.businesses.<BIZ>.rules.<rule-id>=true|false}
 *       (bound in the app module's {@code RegistrationProperties}).</li>
 * </ul>
 *
 * <p><b>Enforced isolation.</b> This module depends on
 * {@code invoice-service-domain} + {@code invoice-mapper} + Lombok only. A
 * {@code bannedDependencies} enforcer rule fails the build if Spring, JDBC, or an adapter
 * module ever leaks in. Rules and the orchestrator are pure Java; construction is a matter of
 * assembling three port stubs. See the test suite for the four-line examples.
 */
package com.example.invoice.service.domain.einvoice;
