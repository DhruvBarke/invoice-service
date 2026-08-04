package com.example.invoice.service.domain.model.invoice;

import lombok.*;
import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TaxCategory {
    private SchemeID id;
    private BigDecimal percent;
    private String taxExemptionReason;
    private CodedValue taxExemptionReasonCode;
    private TaxSchemeRef taxScheme;

    // Convenience accessors
    public String getIdValue() {
        return id != null ? id.getValue() : null;
    }
    public String getTaxExemptionReasonCodeValue() {
        return taxExemptionReasonCode != null ? taxExemptionReasonCode.getValue() : null;
    }
}
