package com.example.invoice.mapper.einvoice.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Person {
    private String firstName;
    private String familyName;
    private String middleName;
    private String jobTitle;
}
