package com.sg.domaininterface.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AdditionalDocumentReference {
    private SchemeID id;
    private String documentDescription;
    private String documentType;
    private String documentTypeCode;
    private Attachment attachment;
}
