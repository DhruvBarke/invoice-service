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

  // ── fee-category labels ──────────────────────────────────────────────────
  public static final String FEE_CATEGORY = "EInvoice";
  public static final String FEE_CATEGORY_CODE = "EINV";

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
