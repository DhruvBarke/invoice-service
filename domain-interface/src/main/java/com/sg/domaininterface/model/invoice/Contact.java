package com.sg.domaininterface.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Contact {
    private String name;
    private String telephone;
    private String telefax;
    private String electronicMail;
}
