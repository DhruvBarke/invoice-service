package com.sg.domain.einvoice.rule;

import com.sg.domaininterface.model.einvoice.error.ErrorCode;
import com.sg.domaininterface.model.einvoice.error.MappingError;
import com.sg.domaininterface.rule.einvoice.ValidationContext;
import com.sg.domaininterface.rule.einvoice.ValidationRule;
import java.util.List;

/**
 * Spec rule 2: an incoming e-invoice must carry at least one attachment — either as a base64
 * blob inside the e-invoice JSON body, or as a multipart file on the HTTP request.
 *
 * <p>Fires under any business unless explicitly disabled. A missing attachment doesn't block
 * mapping (the mapper leaves {@code invoicePdfId} / {@code invoiceExcelId} null anyway), so
 * this rule is what surfaces the situation as an alertable defect.
 */
public final class AttachmentPresentRule implements ValidationRule {

  @Override
  public List<MappingError> check(ValidationContext ctx) {
    if (ctx.hasAnyAttachment()) return List.of();
    return List.of(MappingError.of(
        ErrorCode.MISSING_ATTACHMENT,
        "no attachment present in the e-invoice JSON body or on the multipart request"));
  }
}
