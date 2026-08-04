package com.example.invoice.service.domain.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Attachment {
    private EmbeddedDocument embeddedDocumentBinaryObject;
    private ExternalRef externalReference;
}
