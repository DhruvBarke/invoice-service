package com.example.invoice.mapper.einvoice.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Attachment {
    private EmbeddedDocument embeddedDocumentBinaryObject;
    private ExternalRef externalReference;
}
