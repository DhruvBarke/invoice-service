package com.sg.domaininterface.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DespatchLineReference {
    private String value;
    private String lineId;
    private DocumentReference documentReference;
}
