package com.example.invoice.service.alerting.publish;

import java.util.List;
import java.util.Objects;

public record EmailMessage(List<String> to, String subject, String body) {
    public EmailMessage {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(body, "body");
        to = List.copyOf(Objects.requireNonNull(to, "to"));
        if (to.isEmpty()) {
            throw new IllegalArgumentException("at least one recipient is required");
        }
    }
}
