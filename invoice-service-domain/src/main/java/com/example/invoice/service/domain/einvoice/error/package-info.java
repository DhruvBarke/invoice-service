/**
 * Error taxonomy for the registration pipeline.
 *
 * <p>Three types, one enum, one decision function:
 *
 * <ul>
 *   <li>{@link ErrorCode} — closed set of failure classes, each carrying its own
 *       {@link LifecycleEventType} mapping and a {@code reason_code} matching the
 *       einvoice-service seed.</li>
 *   <li>{@link MappingError} — one occurrence: which code, what detail, which exception (if
 *       any), when.</li>
 *   <li>{@link RegistrationOutcome} + {@link RegistrationOutcome#decide(java.util.List)} — the
 *       precedence rule that turns an error list into the final invoice status + lifecycle
 *       event + reason code + comment for the persistence layer.</li>
 * </ul>
 *
 * <p>Everything else in the registration module — validation rules, the orchestrator, the
 * alert notifier bridge — consumes these three types. The taxonomy is the contract between
 * the pipeline and the operations team.
 */
package com.example.invoice.service.domain.einvoice.error;
