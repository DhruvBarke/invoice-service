package com.example.invoice.service.domain.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Contact {
    private String name;
    private String telephone;
    private String telefax;
    private String electronicMail;
}
