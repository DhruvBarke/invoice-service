/**
 * Driving ports: how the application is asked for registration details.
 *
 * <p>One interface, {@code PartyRegistrationLookup}. The invoice inbound, invoice outbound and report
 * mappers depend on it and on nothing else in the system — a build-time rule enforces that. A mapper
 * unit test supplies a four-line stub and needs no referential, no database and no mail server.
 *
 * <p><b>No application service sits behind this port, deliberately.</b> Strict hexagonal layering
 * would put a use-case orchestrator between the port and the adapter. There is nothing here to
 * orchestrate — no transaction boundary, no multi-step workflow, no cross-aggregate coordination —
 * so such a class would be pure delegation: one extra file per method, and a seam nobody uses. If
 * invoice-specific policy appears later (fallbacks, per-tenant behaviour), it belongs in the
 * composition root, where the invoice context actually lives.
 *
 * <p><b>Failures are reported coarsely on purpose.</b> {@code UnavailabilityReason} tells a caller
 * whether to retry, fail the invoice, or route to an operator. Anything finer would leak cache and
 * quarantine internals into the mappers and couple them to subsystems they must not know about.
 *
 * @readme.section Driving ports (inbound)
 * @readme.order 30
 */
package com.example.invoice.service.domain.port.in;
