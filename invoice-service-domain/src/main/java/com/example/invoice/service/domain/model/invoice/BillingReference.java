package com.example.invoice.service.domain.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BillingReference {
    private InvoiceDocumentReference invoiceDocumentReference;
    private BillingReferenceLine billingReferenceLine;
}
