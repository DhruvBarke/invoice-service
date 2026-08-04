/**
 * Pluggable per-business validation rules.
 *
 * <p>One interface ({@link ValidationRule}), one context type ({@link ValidationContext}),
 * one immutable catalogue ({@link ValidationRegistry}), and four concrete rules that cover
 * spec rules 1–4:
 *
 * <ul>
 *   <li>{@link DuplicateInvoiceRule} — spec rule 1.</li>
 *   <li>{@link AttachmentPresentRule} — spec rule 2.</li>
 *   <li>{@link BrokerageTradeFileRule} — spec rule 3.</li>
 *   <li>{@link LineItemsPresentRule} — spec rule 4 (alert-only, INCOMPLETE status).</li>
 * </ul>
 *
 * <p>Adding a rule is one class + one line in the composition root's builder call. No
 * changes to the orchestrator, the error taxonomy, the alert bridge, or the persistence
 * layer — the whole point of the framework.
 */
package com.example.invoice.service.domain.einvoice.rule;
