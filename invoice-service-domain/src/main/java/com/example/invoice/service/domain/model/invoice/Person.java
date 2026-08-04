package com.example.invoice.service.domain.model.invoice;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Person {
    private String firstName;
    private String familyName;
    private String middleName;
    private String jobTitle;
}
