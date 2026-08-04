package com.example.invoice.service.domain.einvoice.rule;

import com.example.invoice.service.domain.model.invoice.ExtractedAttachment;
import com.example.invoice.service.domain.einvoice.error.ErrorCode;
import com.example.invoice.service.domain.einvoice.error.MappingError;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Spec rule 3: {@code BROKERAGE_PRINCIPAL} and {@code BROKERAGE_AGENCY} fee types require a
 * trade file (.csv or .xlsx). Fires when no such file is present, or the one present has an
 * empty body.
 *
 * <p><b>Corruption is not this rule's concern.</b>
 * {@link com.example.invoice.mapper.einvoice.MultipartExtractionService} already runs magic-byte
 * signature checks (PDF, XLSX, legacy XLS) and drops corrupt files before they reach the
 * context — a corrupt trade file simply doesn't appear here, so this rule sees "no trade file"
 * and fires the same way.
 *
 * <p>Fee-type match is case-insensitive on the extracted marker fee type (which may be
 * {@code BROKERAGE_PRINCIPAL}, {@code brokerage-principal}, etc. — the fee-type matcher
 * normalises spelling but this rule doesn't depend on that having succeeded).
 */
public final class BrokerageTradeFileRule implements ValidationRule {

  private static final Set<String> BROKERAGE_FEETYPES =
      Set.of("BROKERAGE_PRINCIPAL", "BROKERAGE_AGENCY", "BROKERAGEPRINCIPAL", "BROKERAGEAGENCY");

  @Override
  public List<MappingError> check(ValidationContext ctx) {
    String feeType = ctx.marker().feeType();
    if (feeType == null) return List.of();
    String normalized = feeType.toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
    if (!BROKERAGE_FEETYPES.contains(normalized)) return List.of();

    for (ExtractedAttachment a : ctx.attachments()) {
      if (isTradeFile(a)) return List.of();
    }

    // Naming the channel matters here. "Nothing was uploaded" and "nothing was embedded in the
    // document" send the sender to two different places to fix it.
    return List.of(MappingError.of(
        ErrorCode.MISSING_TRADE_FILE,
        "no .csv or .xlsx trade file found for fee type " + feeType
            + " (business " + ctx.business() + " requires one; checked "
            + ctx.attachments().size() + " file(s) on " + ctx.channel() + ")"));
  }

  /**
   * No null-element guard: {@link ValidationContext} copies the attachment list with
   * {@code List.copyOf}, which rejects nulls, so an entry here is always present. A guard would
   * imply a state the context cannot hold.
   */
  private static boolean isTradeFile(ExtractedAttachment a) {
    if (a.bytes() == null || a.bytes().length == 0) return false;
    String name = a.filename();
    if (name == null) return false;
    String lower = name.toLowerCase(Locale.ROOT);
    return lower.endsWith(".csv") || lower.endsWith(".xlsx");
  }
}
