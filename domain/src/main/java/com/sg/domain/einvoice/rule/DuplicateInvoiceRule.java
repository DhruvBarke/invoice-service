package com.sg.domain.einvoice.rule;

import com.sg.domaininterface.model.einvoice.error.ErrorCode;
import com.sg.domaininterface.model.einvoice.error.MappingError;
import com.sg.domaininterface.port.einvoice.ExistingInvoicePayableLookup;
import com.sg.domaininterface.rule.einvoice.ValidationContext;
import com.sg.domaininterface.rule.einvoice.ValidationRule;
import java.util.List;
import java.util.Objects;

/**
 * Spec rule 1: reject if a REGISTERED row already exists in {@code t_invoice_payable} with the
 * same {@code provider_reference}.
 *
 * <p>Fires under any business — duplicates aren't a business-specific concept. Disable per
 * business via {@code invoice.service.registration.businesses.<BIZ>.rules.duplicate-invoice=false}
 * (rare — only makes sense for a business that intentionally re-submits the same reference for
 * different content, in which case the design has bigger problems).
 */
public final class DuplicateInvoiceRule implements ValidationRule {

  private final ExistingInvoicePayableLookup lookup;

  public DuplicateInvoiceRule(ExistingInvoicePayableLookup lookup) {
    this.lookup = Objects.requireNonNull(lookup, "lookup");
  }

  @Override
  public List<MappingError> check(ValidationContext ctx) {
    if (ctx.model() == null || ctx.model().getInvoicePayable() == null) return List.of();
    String ref = ctx.model().getInvoicePayable().getProviderReference();
    if (ref == null || ref.isBlank()) return List.of();
    if (!lookup.existsRegistered(ref)) return List.of();
    return List.of(MappingError.of(
        ErrorCode.DUPLICATE_INVOICE,
        "invoice already exists (provider_reference=" + ref
            + " has a REGISTERED row in t_invoice_payable)"));
  }
}
