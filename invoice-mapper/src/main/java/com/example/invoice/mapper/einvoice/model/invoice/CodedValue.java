package com.example.invoice.mapper.einvoice.model.invoice;

import lombok.*;

/**
 * Coded value with list-based metadata attributes.
 * Used for UBL elements like InvoiceTypeCode, DocumentCurrencyCode,
 * PaymentMeansCode, Country/IdentificationCode, TaxExemptionReasonCode, etc.
 *
 * Maps attributes: listID, listAgencyID (UN/ECE code list references).
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CodedValue {
    private String listID;
    private String listAgencyID;
    private String value;

    public static CodedValue fromString(String val) {
        return new CodedValue(null, null, val);
    }
}
