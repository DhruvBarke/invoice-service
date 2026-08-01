package com.example.invoice.mapper.einvoice.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BillingReference {
    private InvoiceDocumentReference invoiceDocumentReference;
    private BillingReferenceLine billingReferenceLine;
}
