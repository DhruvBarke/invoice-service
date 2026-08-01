package com.example.invoice.service.domain.rule;

import com.example.invoice.service.domain.model.PartyRegistrationDetails;
import java.util.List;
import java.util.Objects;

/**
 * One detected defect, bound to the record that carries it.
 *
 * @param subject the offending record, or {@code null} for {@link AnomalyType#NO_REGISTRATION_FOUND}
 *                where there is no record at all
 */
public record Anomaly(AnomalyType type, String detail, PartyRegistrationDetails subject) {

    public Anomaly {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(detail, "detail");
    }

    public static Anomaly of(AnomalyType type, String detail) {
        return new Anomaly(type, detail, null);
    }

    public static Anomaly of(AnomalyType type, String detail, PartyRegistrationDetails subject) {
        return new Anomaly(type, detail, subject);
    }

    public boolean isBlocking() {
        return type.isBlocking();
    }

    /**
     * @return {@link Servability#BLOCKING} when any finding blocks.
     *
     * <p>Placed here rather than at each call site so the aggregation rule — one blocking defect
     * blocks the whole response — is stated once and cannot be applied inconsistently.
     */
    public static Servability servabilityOf(List<Anomaly> anomalies) {
        for (Anomaly a : anomalies) {
            if (a.isBlocking()) {
                return Servability.BLOCKING;
            }
        }
        return Servability.SERVABLE;
    }
}
