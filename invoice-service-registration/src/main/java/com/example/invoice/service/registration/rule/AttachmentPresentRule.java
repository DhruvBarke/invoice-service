package com.example.invoice.service.registration.rule;

import com.example.invoice.service.registration.error.ErrorCode;
import com.example.invoice.service.registration.error.MappingError;
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
