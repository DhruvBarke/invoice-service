package com.example.invoice.config;

import com.example.invoice.service.alerting.AlertingSwitches;
import com.example.invoice.service.alerting.QuarantiningResponseGuard;
import com.example.invoice.service.alerting.publish.AlertEmailPort;
import com.example.invoice.service.alerting.publish.EmailAlertConfig;
import com.example.invoice.service.alerting.publish.EmailAlertPublisher;
import com.example.invoice.service.alerting.publish.SwitchGatedNotifier;
import com.example.invoice.service.alerting.quarantine.JdbcQuarantineStore;
import com.example.invoice.service.alerting.quarantine.QuarantinePoller;
import com.example.invoice.service.alerting.quarantine.QuarantineService;
import com.example.invoice.service.alerting.quarantine.RecordCodec;
import com.example.invoice.service.cache.CachingPartyRegistrationLookup;
import com.example.invoice.service.cache.InboundPartyRegistrationCache;
import com.example.invoice.service.cache.OutboundPartyRegistrationCache;
import com.example.invoice.service.domain.model.KeySpace;
import com.example.invoice.service.domain.port.in.PartyRegistrationLookup;
import com.example.invoice.service.domain.port.out.AlertNotifier;
import com.example.invoice.service.domain.port.out.QuarantineStore;
import com.example.invoice.service.domain.port.out.ReferentialGateway;
import com.example.invoice.service.domain.port.out.ResponseGuard;
import com.example.invoice.service.domain.rule.AnomalyDetector;
import com.example.invoice.service.domain.rule.DetectionPolicy;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** See the package documentation for the rationale behind explicit wiring. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PartyRegistrationProperties.class, AlertingProperties.class})
public class PartyRegistrationConfig {

    // ------------------------------------------------------------------ rules

    @Bean
    public AnomalyDetector anomalyDetector(AlertingProperties props) {
        AlertingProperties.Detection d = props.getDetection();
        return new AnomalyDetector(new DetectionPolicy(
                d.isInboundMissingSiret(), d.isOutboundMissingSiret(),
                d.isInboundGoldenMismatch(), d.isOutboundGoldenMismatch()));
    }

    // ------------------------------------------------------------------ notification

    /** A bean so an operations endpoint can flip switches at runtime, without a restart. */
    @Bean
    public AlertingSwitches alertingSwitches(AlertingProperties props) {
        AlertingProperties.Email cfg = props.getEmail();
        AlertingSwitches switches = new AlertingSwitches();
        switches.setEmailEnabled(cfg.isEnabled());
        switches.setAnomalyTypes(cfg.getAnomalyTypes());
        switches.setFlows(cfg.getFlows());
        switches.setMinimumServability(cfg.getMinimumServability());
        return switches;
    }

    @Bean(destroyMethod = "close")
    public EmailAlertPublisher emailAlertPublisher(AlertEmailPort emailPort, AlertingProperties props) {
        AlertingProperties.Email cfg = props.getEmail();
        return new EmailAlertPublisher(emailPort, new EmailAlertConfig(
                cfg.getRecipients(), cfg.getSubjectPrefix(), cfg.getDigestInterval(),
                cfg.getImmediateServability(), cfg.getMaxFingerprints(), cfg.getMaxRetries(),
                Duration.ofSeconds(2), Duration.ofSeconds(10)));
    }

    @Bean
    public AlertNotifier alertNotifier(AlertingSwitches switches, EmailAlertPublisher email) {
        return new SwitchGatedNotifier(switches, email);
    }

    // ------------------------------------------------------------------ quarantine

    @Bean
    public QuarantineStore quarantineStore(DataSource dataSource, RecordCodec codec) {
        return new JdbcQuarantineStore(dataSource, codec);
    }

    @Bean
    public QuarantineService quarantineService(QuarantineStore store, AlertNotifier notifier) {
        return new QuarantineService(store, notifier);
    }

    /**
     * The guard.
     *
     * <p>{@code alerting.enabled=false} returns the pass-through implementation: no detection, no
     * recording, and <b>no blocking</b>. Deliberately a separate property from the email switches,
     * so that silencing mail can never disable a safety control.
     */
    @Bean
    public ResponseGuard responseGuard(AnomalyDetector detector, QuarantineService service,
                                        AlertingProperties props) {
        if (!props.isEnabled()) {
            return ResponseGuard.passThrough();
        }
        return new QuarantiningResponseGuard(detector, service,
                props.getQuarantine().isAutoRetireResolved());
    }

    // ------------------------------------------------------------------ caches

    @Bean(destroyMethod = "close")
    public InboundPartyRegistrationCache inboundPartyRegistrationCache(
            ReferentialGateway gateway, PartyRegistrationProperties props, ResponseGuard guard) {
        return new InboundPartyRegistrationCache(gateway,
                props.getSiren().toCacheConfig(), props.getSiret().toCacheConfig(), guard);
    }

    @Bean(destroyMethod = "close")
    public OutboundPartyRegistrationCache outboundPartyRegistrationCache(
            ReferentialGateway gateway, PartyRegistrationProperties props, ResponseGuard guard) {
        return new OutboundPartyRegistrationCache(gateway, props.getBdrId().toCacheConfig(), guard);
    }

    /** The driving-port bean the invoice mappers inject. */
    @Bean
    public PartyRegistrationLookup partyRegistrationLookup(InboundPartyRegistrationCache inbound,
                                                            OutboundPartyRegistrationCache outbound) {
        return new CachingPartyRegistrationLookup(inbound, outbound);
    }

    /** Propagates corrections across instances. See {@link QuarantinePoller} for why polling. */
    @Bean(initMethod = "start", destroyMethod = "close")
    public QuarantinePoller quarantinePoller(QuarantineStore store, AlertingProperties props,
                                              InboundPartyRegistrationCache inbound,
                                              OutboundPartyRegistrationCache outbound) {
        return new QuarantinePoller(store, props.getQuarantine().getPollInterval(),
                (keySpace, key) -> {
                    switch (KeySpace.valueOf(keySpace)) {
                        case SIREN -> inbound.invalidate(KeySpace.SIREN, key);
                        case SIRET -> inbound.invalidate(KeySpace.SIRET, key);
                        case BDR_ID -> outbound.invalidate(key);
                    }
                });
    }
}
