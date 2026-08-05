package com.sg.bootstrap.config;

import com.sg.domaininterface.model.party.Flow;
import com.sg.domaininterface.rule.party.AnomalyType;
import com.sg.domaininterface.rule.party.Servability;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <p><b>{@code enabled} and {@code email.enabled} are different things and must not be confused.</b>
 * Turning {@code email.enabled} off silences mail while every defect is still detected, recorded,
 * and — when blocking — withheld from invoice registration. Turning {@code enabled} off removes the
 * guard entirely: nothing is recorded and nothing is blocked. Only the second is a safety decision,
 * which is why they are separate properties rather than one flag.
 */
@ConfigurationProperties(prefix = "invoice.service.alerting")
public class AlertingProperties {

    /**
     * SAFETY SWITCH. False installs {@code ResponseGuard.passThrough()}: no detection, no quarantine
     * rows, no blocking. This is not how to stop emails — see {@link Email#enabled}.
     */
    private boolean enabled = true;

    private final Detection detection = new Detection();
    private final Email email = new Email();
    private final Quarantine quarantine = new Quarantine();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public Detection getDetection() { return detection; }
    public Email getEmail() { return email; }
    public Quarantine getQuarantine() { return quarantine; }

    /**
     * Governs what is DETECTED, RECORDED and BLOCKED.
     *
     * <p>The two blocking checks — nothing returned, and a record with no usable SIREN — are not
     * here and cannot be switched off. Disabling either would not make the data usable; it would
     * only stop anyone being told, and the invoice would register against a party that does not
     * exist.
     */
    public static class Detection {
        private boolean inboundMissingSiret = true;
        private boolean outboundMissingSiret = true;
        private boolean inboundGoldenMismatch = true;
        /** Off by default: an outbound lookup often carries an elementary id deliberately. */
        private boolean outboundGoldenMismatch = false;

        /** Several parties for one SIREN. Advisory — a golden record is still selected. */
        private boolean inboundMultipleRegistrations = true;

        /** Several parties for one BDR id. */
        private boolean outboundMultipleRegistrations = true;

        public boolean isInboundMissingSiret() { return inboundMissingSiret; }
        public void setInboundMissingSiret(boolean v) { this.inboundMissingSiret = v; }
        public boolean isOutboundMissingSiret() { return outboundMissingSiret; }
        public void setOutboundMissingSiret(boolean v) { this.outboundMissingSiret = v; }
        public boolean isInboundGoldenMismatch() { return inboundGoldenMismatch; }
        public void setInboundGoldenMismatch(boolean v) { this.inboundGoldenMismatch = v; }
        public boolean isOutboundGoldenMismatch() { return outboundGoldenMismatch; }
        public void setOutboundGoldenMismatch(boolean v) { this.outboundGoldenMismatch = v; }
        public boolean isInboundMultipleRegistrations() { return inboundMultipleRegistrations; }
        public void setInboundMultipleRegistrations(boolean v) {
            this.inboundMultipleRegistrations = v;
        }
        public boolean isOutboundMultipleRegistrations() { return outboundMultipleRegistrations; }
        public void setOutboundMultipleRegistrations(boolean v) {
            this.outboundMultipleRegistrations = v;
        }
    }

    /** Governs only what is SENT. Never affects detection, recording or blocking. */
    public static class Email {
        private boolean enabled = true;
        private List<String> recipients = List.of();
        private String subjectPrefix = "[party-referential]";
        private Duration digestInterval = Duration.ofMinutes(5);
        private Servability minimumServability = Servability.SERVABLE;
        private Servability immediateServability = Servability.BLOCKING;
        private Set<AnomalyType> anomalyTypes = EnumSet.allOf(AnomalyType.class);
        private Set<Flow> flows = EnumSet.allOf(Flow.class);
        private int maxFingerprints = 500;
        private int maxRetries = 3;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public List<String> getRecipients() { return recipients; }
        public void setRecipients(List<String> v) { this.recipients = v; }
        public String getSubjectPrefix() { return subjectPrefix; }
        public void setSubjectPrefix(String v) { this.subjectPrefix = v; }
        public Duration getDigestInterval() { return digestInterval; }
        public void setDigestInterval(Duration v) { this.digestInterval = v; }
        public Servability getMinimumServability() { return minimumServability; }
        public void setMinimumServability(Servability v) { this.minimumServability = v; }
        public Servability getImmediateServability() { return immediateServability; }
        public void setImmediateServability(Servability v) { this.immediateServability = v; }
        public Set<AnomalyType> getAnomalyTypes() { return anomalyTypes; }
        public void setAnomalyTypes(Set<AnomalyType> v) { this.anomalyTypes = v; }
        public Set<Flow> getFlows() { return flows; }
        public void setFlows(Set<Flow> v) { this.flows = v; }
        public int getMaxFingerprints() { return maxFingerprints; }
        public void setMaxFingerprints(int v) { this.maxFingerprints = v; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int v) { this.maxRetries = v; }
    }

    public static class Quarantine {
        private Duration pollInterval = Duration.ofSeconds(30);
        /** Retire a row automatically once the referential response is clean again. */
        private boolean autoRetireResolved = true;

        public Duration getPollInterval() { return pollInterval; }
        public void setPollInterval(Duration v) { this.pollInterval = v; }
        public boolean isAutoRetireResolved() { return autoRetireResolved; }
        public void setAutoRetireResolved(boolean v) { this.autoRetireResolved = v; }
    }
}
