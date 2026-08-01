package com.example.invoice.mapper.einvoice.model.invoice;

import lombok.*;
import java.time.LocalDate;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Period {
    private String descriptionCode;
    private LocalDate startDate;
    private LocalDate endDate;
}
