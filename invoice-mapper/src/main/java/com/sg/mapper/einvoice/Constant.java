package com.sg.mapper.einvoice;









/**
 * Constants shared across the einvoice mappers.
 *
 * <p>Vendored verbatim from A's {@code com.sg.domaininterface.common.Constant} (see
 * {@code invoice-service-mapping/src/main/java/com/sg/domaininterface/common/}). Kept as a
 * {@code final} class with private constructor rather than an interface so the constants
 * cannot leak into unrelated implementations by accident.
 */
public final class Constant {

  private Constant() {}

  // ── UNTDID 1001 invoice-type codes ───────────────────────────────────────
  public static final String INVOICE_TYPE_DEBIT = "380";
  public static final String INVOICE_TYPE_CREDIT = "381";
  public static final String INVOICE_TYPE_CORRECTED = "384";

  // ── canonical invoice-type labels stored in InvoicePayableModel ─────────
  public static final String DEBIT = "DEBIT";
  public static final String CREDIT = "CREDIT";
  public static final String CORRECTED = "CORRECTED";
  public static final String UNKNOWN = "UNKNOWN";

  // ── invoice-status labels ────────────────────────────────────────────────
  public static final String INVOICE_STATUS_REGISTERED = "REGISTERED";

  /**
   * The value written to {@code created_by_user} / {@code last_updated_by_user}.
   *
   * <p>Every other producer of these rows records the person who captured the invoice, taken from
   * the authenticated principal. Nobody captures an e-invoice — it arrives — so there is no person
   * to record and the columns were being written null. Null there reads as "the capture user was
   * lost", which is a defect someone would go looking for; naming the pipeline says plainly that
   * no person was involved, and matches what {@code invoice_flow} already says about the row.
   */
  public static final String EINVOICE_USER = "EINVOICE";

  // ── fee-category labels ──────────────────────────────────────────────────

  /**
   * The two fee categories whose invoices go through trade reconciliation.
   *
   * <p>Ids rather than names, because {@code t_invoice_payable.fee_category} holds the id. Taken
   * from the manual registration path, which is what decides this today; a row that skips the
   * decision is one reconciliation never picks up.
   */
  public static final String ELECTRONIC_BROKER_FEE_CATEGORY_ID = "73";

  public static final String PRINCIPAL_FEE_CATEGORY_ID = "11";

  // ── reconciliation states ────────────────────────────────────────────────
  public static final String RECON_TO_BE_PROCESSED = "TO_BE_PROCESSED";
  public static final String RECON_NOT_APPLICABLE = "NOT_APPLICABLE";

  // ── provider defaults (synthetic) ────────────────────────────────────────
  public static final String PROVIDER_MNEMO = "EINV";

  // ── currency / tax defaults ──────────────────────────────────────────────
  public static final String DEFAULT_VAT_SCHEME = "VAT";
  public static final String DEFAULT_TAX_CATEGORY = "S";

  // ── UBL profile defaults (CPRO France) ───────────────────────────────────
  public static final String UBL_VERSION_ID = "2.1";
  public static final String CUSTOMIZATION_ID = "urn.cpro.gouv.fr:1p0:einvoicingextract#";
  public static final String PROFILE_ID = "S1";

  // ── default address (Paris) ──────────────────────────────────────────────
  public static final String DEFAULT_CITY = "PARIS";
  public static final String DEFAULT_POSTAL_ZONE = "75009";
  public static final String DEFAULT_COUNTRY = "FR";
}
