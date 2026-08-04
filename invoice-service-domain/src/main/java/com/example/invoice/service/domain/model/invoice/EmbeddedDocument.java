package com.example.invoice.service.domain.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class EmbeddedDocument {
    private String mimeCode;
    private String filename;
    private String file;
}
