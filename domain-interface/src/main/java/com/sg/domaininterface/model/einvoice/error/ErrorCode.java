package com.sg.domaininterface.model.einvoice.error;









/**
 * Central taxonomy of registration failure classes.
 *
 * <p>Each value carries:
 * <ul>
 *   <li>a stable {@link #code() short identifier} (e.g. {@code DUP-001}) that operations can
 *       reference in tickets and runbooks — never renumbered, only marked deprecated;</li>
 *   <li>a human-readable {@link #description()} for the alert email body;</li>
 *   <li>a {@link #lifecycleEvent()} — {@code REFUSED}, {@code SUSPENDED}, or {@code null}
 *       (alert-only, no event fired);</li>
 *   <li>a {@link #reasonCode()} matching the einvoice-service {@code t_reason_code_status}
 *       seed so the scheduler that picks failed invoices out of {@code t_invoice_payable}
 *       knows what CDAR reason to embed in the lifecycle event it posts back.</li>
 * </ul>
 *
 * <p><b>Extending.</b> Add a value at the bottom, keep the code prefix meaningful (e.g.
 * {@code DUP-*} for duplicates, {@code ATT-*} for attachments). Do not renumber existing
 * codes; downstream systems key on them.
 *
 * <p><b>Reason-code choices are copied from</b>
 * {@code bootstrap/src/main/resources/db/migration/V4__seed_reason_code_status.sql} in the
 * einvoice-service:
 * <ul>
 *   <li>{@code DOUBLON} — duplicate invoice (fires under 210 REFUSED).</li>
 *   <li>{@code NON_CONFORME} — invoice does not conform to expected shape (fires under 210).</li>
 *   <li>{@code SIRET_ERR} — party identifier resolution failed (fires under 210, and under 208
 *       when the failure is transient).</li>
 *   <li>{@code JUSTIF_ABS} — supporting justification/attachment absent (fires under 208).</li>
 *   <li>{@code REF_CT_ABSENT} — required content reference absent (fires under 208).</li>
 * </ul>
 */
public enum ErrorCode {

  // ── Marker / fee-type resolution ─────────────────────────────────────────
  MARKER_MALFORMED(
      "MRK-001",
      "Accounting-customer-party endpoint value is missing or does not match "
          + "<siren>_<BUSINESS>_<FEETYPE>",
      LifecycleEventType.REFUSED, "NON_CONFORME"),

  BUSINESS_UNKNOWN(
      "MRK-002",
      "Business token in the endpoint marker does not match any known Business enum value",
      LifecycleEventType.REFUSED, "NON_CONFORME"),

  /**
   * The token matched more than one fee type and nothing broke the tie.
   *
   * <p>Separate from {@link #FEETYPE_UNRESOLVED} because the two need different answers from the
   * sender. Unresolved means the token matched nothing — they sent something we do not have.
   * Ambiguous means they sent something that matches several, and the fix is to send the fuller
   * name; a bare {@code BROKERAGE} ties between the principal and agency variants. Reporting
   * both as "unresolved" sends them looking for a fee type that is in fact there.
   */
  FEETYPE_AMBIGUOUS(
      "FEE-002",
      "Fee type from the endpoint marker matched more than one entry in the referential and "
          + "could not be resolved to exactly one",
      LifecycleEventType.REFUSED, "NON_CONFORME"),

  FEETYPE_UNRESOLVED(
      "FEE-001",
      "Fee type from the endpoint marker could not be resolved against the fee-type referential",
      LifecycleEventType.REFUSED, "NON_CONFORME"),

  // ── Party referential ────────────────────────────────────────────────────
  PARTY_LOOKUP_FAILED(
      "PTY-001",
      "Party registration lookup failed (referential unavailable or entry not found)",
      LifecycleEventType.SUSPENDED, "SIRET_ERR"),

  // ── Duplicates ───────────────────────────────────────────────────────────
  DUPLICATE_INVOICE(
      "DUP-001",
      "An invoice with the same provider reference is already REGISTERED in t_invoice_payable",
      LifecycleEventType.REFUSED, "DOUBLON"),

  // ── Attachments ──────────────────────────────────────────────────────────
  MISSING_ATTACHMENT(
      "ATT-001",
      "No attachment found in the e-invoice JSON nor supplied as a multipart file",
      LifecycleEventType.SUSPENDED, "JUSTIF_ABS"),

  MISSING_TRADE_FILE(
      "TRD-001",
      "Brokerage business requires a .csv or .xlsx trade file — none found, or file is empty/corrupt",
      LifecycleEventType.SUSPENDED, "JUSTIF_ABS"),

  /**
   * The document arrived and could not be stored.
   *
   * <p>Alert-only, and deliberately not a SUSPENDED event. {@code MISSING_ATTACHMENT} says the
   * sender did not attach anything, which is theirs to fix; this says they attached something
   * and the store would not take it, which is ours. Refusing the invoice for our own outage
   * would ask them to resend a document that was never the problem.
   *
   * <p>The row keeps a null {@code sg_doc_id}, which is the honest record that a document
   * arrived and its content is not yet retrievable.
   */
  DOCUMENT_UPLOAD_FAILED(
      "ATT-002",
      "An attachment was received but could not be stored in SGDoc. "
          + "The document row records it with no handle until an upload succeeds.",
      null, null),

  // ── Line items ───────────────────────────────────────────────────────────
  /**
   * Alert-only. The invoice is stored with {@code INCOMPLETE} status so users can add lines
   * later; no lifecycle event is emitted because the sender's payload isn't refusable.
   */
  EMPTY_LINE_ITEMS(
      "LIN-001",
      "No line items found for a fee category that requires them "
          + "(CUSTODY / EXCHANGE / CLEARING). Invoice stored as INCOMPLETE for user completion.",
      null, null),

  /**
   * The registration could not be written.
   *
   * <p>No lifecycle event, and not because it is minor. A REFUSED or SUSPENDED event tells the
   * sender to do something about their invoice, and there is nothing wrong with it — the failure
   * is entirely on this side. Telling them to correct and resend would be wrong twice: it blames
   * them for our outage, and their resend hits the same broken database.
   *
   * <p>Unlike every other code here, this one cannot be recorded on the row it describes: the
   * row is what failed to write. It reaches an operator through the alert, and the caller is
   * told the invoice was not stored so it can be sent again once the fault is cleared.
   */
  PERSISTENCE_FAILED(
      "SYS-001",
      "The registration could not be written to the database. The invoice was NOT stored.",
      null, null),

  // ── Generic mapping errors ───────────────────────────────────────────────
  /**
   * Catch-all for any {@link RuntimeException} thrown by the mapping stack that doesn't fit a
   * more specific code above. The exception message is captured on the {@link MappingError}.
   */
  MAPPING_ERROR(
      "MAP-001",
      "Unhandled exception during e-invoice → InvoicePayable mapping",
      LifecycleEventType.REFUSED, "NON_CONFORME");

  private final String code;
  private final String description;
  private final LifecycleEventType lifecycleEvent;
  private final String reasonCode;

  ErrorCode(String code, String description,
            LifecycleEventType lifecycleEvent, String reasonCode) {
    this.code = code;
    this.description = description;
    this.lifecycleEvent = lifecycleEvent;
    this.reasonCode = reasonCode;
  }

  public String code() {
    return code;
  }

  public String description() {
    return description;
  }

  /** {@code null} when this error triggers no lifecycle event (alert-only). */
  public LifecycleEventType lifecycleEvent() {
    return lifecycleEvent;
  }

  /** {@code null} paired with {@link #lifecycleEvent()} == null. */
  public String reasonCode() {
    return reasonCode;
  }
}
