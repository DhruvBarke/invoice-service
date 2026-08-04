package com.sg.domaininterface.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DocumentReference {
    private String id;
    private String schemeID;
    private String documentDescription;
    private Attachment attachment;
}
