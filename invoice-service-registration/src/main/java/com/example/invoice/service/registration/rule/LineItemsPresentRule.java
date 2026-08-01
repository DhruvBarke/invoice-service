package com.example.invoice.service.registration.rule;

import com.example.invoice.service.registration.error.ErrorCode;
import com.example.invoice.service.registration.error.MappingError;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Spec rule 4: {@code CUSTODY}, {@code EXCHANGE}, and {@code CLEARING} fee types require at
 * least one line item. Fires when the mapping produced an empty item list.
 *
 * <p><b>Non-fatal.</b> This is the one rule whose {@link ErrorCode#EMPTY_LINE_ITEMS} maps to
 * no lifecycle event: the invoice is stored with status {@code INCOMPLETE} so users can add
 * lines later in the UI. Alert still fires so operations knows the row is sitting there. See
 * point 8 in the spec and the precedence rule on {@link
 * com.example.invoice.service.registration.error.RegistrationOutcome#decide}.
 */
public final class LineItemsPresentRule implements ValidationRule {

  private static final Set<String> FEETYPES_REQUIRING_LINES =
      Set.of("CUSTODY", "EXCHANGE", "CLEARING");

  @Override
  public List<MappingError> check(ValidationContext ctx) {
    String feeType = ctx.marker().feeType();
    if (feeType == null) return List.of();
    String normalized = feeType.toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
    if (!FEETYPES_REQUIRING_LINES.contains(normalized)) return List.of();
    if (ctx.items() != null && !ctx.items().isEmpty()) return List.of();
    return List.of(MappingError.of(
        ErrorCode.EMPTY_LINE_ITEMS,
        "no line items found for fee type " + feeType
            + " (business " + ctx.business() + " requires at least one)"));
  }
}
