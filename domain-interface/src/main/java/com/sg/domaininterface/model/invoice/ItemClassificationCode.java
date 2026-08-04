package com.sg.domaininterface.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ItemClassificationCode {
    private String listID;
    private String listVersionID;
    private String listAgencyID;
    private String value;
}
