package com.sg.bootstrap.config;

import com.sg.alert.AlertingSwitches;
import com.sg.domain.quarantine.QuarantiningResponseGuard;
import com.sg.domaininterface.port.out.AlertEmailPort;
import com.sg.domaininterface.model.alerting.EmailAlertConfig;
import com.sg.alert.EmailAlertPublisher;
import com.sg.alert.SwitchGatedNotifier;
import com.sg.jpa.adapter.JdbcQuarantineStore;
import com.sg.alert.QuarantinePoller;
import com.sg.domain.quarantine.QuarantineService;
import com.sg.domaininterface.port.out.RecordCodec;
import com.sg.caching.CachingPartyRegistrationLookup;
import com.sg.caching.InboundPartyRegistrationCache;
import com.sg.caching.OutboundPartyRegistrationCache;
import com.sg.domaininterface.model.party.KeySpace;
import com.sg.domaininterface.port.out.PartyRegistrationLookup;
import com.sg.domaininterface.port.out.AlertNotifier;
import com.sg.domaininterface.port.out.QuarantineStore;
import com.sg.domaininterface.port.thirdparty.PartyReferentialService;
import com.sg.domaininterface.port.out.ResponseGuard;
import com.sg.domain.party.AnomalyDetector;
import com.sg.domaininterface.rule.party.DetectionPolicy;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** See the package documentation for the rationale behind explicit wiring. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PartyRegistrationProperties.class, AlertingProperties.class})
public class PartyRegistrationConfig {

    // ------------------------------------------------------------------ rules

    @Bean
    public AnomalyDetector anomalyDetector(AlertingProperties props) {
        AlertingProperties.Detection d = props.getDetection();
        // Named setters rather than six positional booleans: the compiler is perfectly happy
        // with inbound and outbound the wrong way round, and the result is a check running on
        // exactly the flow it was configured off for.
        return new AnomalyDetector(DetectionPolicy.builder()
                .missingSiret(d.isInboundMissingSiret(), d.isOutboundMissingSiret())
                .goldenMismatch(d.isInboundGoldenMismatch(), d.isOutboundGoldenMismatch())
                .multipleRegistrations(
                        d.isInboundMultipleRegistrations(), d.isOutboundMultipleRegistrations())
                .build());
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

    /**
     * The notifier everything else should be given.
     *
     * <p>{@code @Primary} because two beans are {@link AlertNotifier}s and, unlike a redundant
     * pair, both are wanted: the publisher below does the sending, and this one wraps it with the
     * configured switches. Without the marker Spring cannot choose and the context will not
     * start; worse, resolving it by name instead would hand a caller the UNGATED publisher, and
     * every switch in {@code invoice.service.alerting} would be quietly bypassed.
     *
     * <p>The publisher still needs a definition of its own — it is injected here by concrete
     * type, and its {@code destroyMethod} is what flushes the pending digest on shutdown.
     */
    @Bean
    @Primary
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
            PartyReferentialService referential, PartyRegistrationProperties props, ResponseGuard guard) {
        return new InboundPartyRegistrationCache(referential,
                props.getSiren().toCacheConfig(), props.getSiret().toCacheConfig(), guard);
    }

    @Bean(destroyMethod = "close")
    public OutboundPartyRegistrationCache outboundPartyRegistrationCache(
            PartyReferentialService referential, PartyRegistrationProperties props, ResponseGuard guard) {
        return new OutboundPartyRegistrationCache(referential, props.getBdrId().toCacheConfig(), guard);
    }

    /** The lookup the invoice mappers inject — a cache in front of the referential. */
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
