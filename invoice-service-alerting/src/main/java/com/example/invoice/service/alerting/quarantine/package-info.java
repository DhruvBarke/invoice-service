/**
 * Persistence and correction workflow for detected defects.
 *
 * <p><b>The table is the system of record.</b> Notification is a courtesy on top of it. Every defect
 * lands here whether or not anyone is emailed, which is why silencing mail is safe.
 *
 * <p><b>Order of precedence.</b> An active corrected row outranks the referential, always. That is
 * the point: once an operator fixes a value, invoice processing uses it immediately without waiting
 * for the upstream source to be corrected. A retired row is inert, so the referential value flows
 * again.
 *
 * <p><b>Corrections are validated before use.</b> A corrected payload with no usable SIREN would
 * otherwise be served straight into invoice registration with the row marked resolved — a defect
 * laundered into looking fixed, which is worse than the original problem because nobody is looking
 * for it any more.
 *
 * <p><b>Retirement re-arms detection.</b> Once a row is soft-deleted the raw value flows again; if it
 * is still defective, detection re-fires and a fresh row opens with a new notification. Correct for a
 * premature retirement, and worth knowing about because it surprises people.
 *
 * <p><b>The schema uses a nullable {@code active_flag} rather than a partial index.</b> NULL never
 * collides in a unique index, so soft-deleted rows survive as history while a new row can open on the
 * same key — portable across Postgres, Oracle and H2 without dialect-specific DDL.
 *
 * @readme.section Quarantine and corrections
 * @readme.order 70
 */
package com.example.invoice.service.alerting.quarantine;
