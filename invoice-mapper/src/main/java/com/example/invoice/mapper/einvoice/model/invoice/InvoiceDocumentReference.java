package com.example.invoice.mapper.einvoice.model.invoice;

import lombok.*;
import java.time.LocalDate;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class InvoiceDocumentReference {
    private String id;
    private LocalDate issueDate;
    private String documentTypeCode;
    private String documentStatusCode;
}
