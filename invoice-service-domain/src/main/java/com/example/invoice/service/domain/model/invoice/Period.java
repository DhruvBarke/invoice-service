package com.example.invoice.service.domain.model.invoice;

import lombok.*;
import java.time.LocalDate;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Period {
    private String descriptionCode;
    private LocalDate startDate;
    private LocalDate endDate;
}
