package com.sg.bootstrap.config;

import com.sg.domaininterface.port.thirdparty.BusinessCalendarService;
import com.sg.domaininterface.port.thirdparty.CurrencyConverterService;
import com.sg.domaininterface.port.thirdparty.FeeCategoryReferentialService;
import com.sg.domaininterface.port.thirdparty.PartyReferentialService;
import com.sg.domaininterface.port.out.AlertEmailPort;
import com.sg.domaininterface.port.thirdparty.SgDocReferentialService;
import com.sg.domaininterface.port.thirdparty.SsiReferentialService;
import com.sg.thirdparties.ReferentialProperties;
import com.sg.thirdparties.RestBusinessCalendarClient;
import com.sg.thirdparties.RestCurrencyReferentialClient;
import com.sg.thirdparties.RestFeeCategoryReferentialClient;
import com.sg.thirdparties.RestPartyReferentialClient;
import com.sg.thirdparties.RestEmailReferentialClient;
import com.sg.thirdparties.RestSgDocReferentialClient;
import com.sg.thirdparties.RestSsiReferentialClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * The referential clients: party registration, fee categories and SGDoc.
 *
 * <p>All three are constructed here rather than annotated in {@code third-parties}, so that
 * module stays a plain library — its clients take a {@link RestTemplate} and three URLs, and can
 * be built in a test with neither a container nor a property file.
 *
 * <p><b>One RestTemplate, with timeouts set.</b> The default has none at all: a referential that
 * accepts a connection and then stops responding would hold the calling thread until the socket
 * gave up, which on some platforms is never. Under load that exhausts the request pool and the
 * whole service stops answering because one dependency is slow — the failure looks like an
 * invoice-service outage rather than a referential one.
 */
@Configuration
@EnableConfigurationProperties(ReferentialUrlProperties.class)
public class ReferentialConfig {

    @Bean
    public RestTemplate referentialRestTemplate(RestTemplateBuilder builder,
                                                ReferentialUrlProperties props) {
        return builder
                .setConnectTimeout(Duration.ofMillis(props.getConnectTimeoutMillis()))
                .setReadTimeout(Duration.ofMillis(props.getReadTimeoutMillis()))
                .build();
    }

    @Bean
    public ReferentialProperties referentialProperties(ReferentialUrlProperties props) {
        return new ReferentialProperties(
                props.getPartyBaseUrl(), props.getFeeCategoryBaseUrl(), props.getSgDocBaseUrl(),
                props.getEmailBaseUrl(), props.getCommonBaseUrl());
    }

    /**
     * The rate used to express a foreign-currency invoice in euros.
     *
     * <p>Consulted once per non-euro invoice, at registration. Not cached: the rate is asked for
     * as at a past date, so a cache would be keyed on a date that changes with every invoice and
     * would hold entries nothing asks for twice.
     */
    @Bean
    public CurrencyConverterService currencyConverterService(
            RestTemplate referentialRestTemplate, ReferentialProperties properties) {
        return new RestCurrencyReferentialClient(referentialRestTemplate, properties);
    }

    @Bean
    public BusinessCalendarService businessCalendarService(
            RestTemplate referentialRestTemplate, ReferentialProperties properties) {
        return new RestBusinessCalendarClient(referentialRestTemplate, properties);
    }

    @Bean
    public SsiReferentialService ssiReferentialService(
            RestTemplate referentialRestTemplate, ReferentialProperties properties) {
        return new RestSsiReferentialClient(referentialRestTemplate, properties);
    }

    @Bean
    public PartyReferentialService partyReferentialService(RestTemplate referentialRestTemplate,
                                                           ReferentialProperties properties) {
        return new RestPartyReferentialClient(referentialRestTemplate, properties);
    }

    @Bean
    public FeeCategoryReferentialService feeCategoryReferentialService(
            RestTemplate referentialRestTemplate, ReferentialProperties properties) {
        return new RestFeeCategoryReferentialClient(referentialRestTemplate, properties);
    }

    @Bean
    public SgDocReferentialService sgDocReferentialService(RestTemplate referentialRestTemplate,
                                                           ReferentialProperties properties) {
        return new RestSgDocReferentialClient(referentialRestTemplate, properties);
    }

    /**
     * The mail transport the alerting stack sends through.
     *
     * <p>Previously absent. {@code AlertEmailPort} was declared, {@code EmailAlertPublisher}
     * was written and tested against it, and no bean ever answered it — so the context could
     * not start. Every alert this service raises passes through here.
     */
    @Bean
    public AlertEmailPort alertEmailPort(RestTemplate referentialRestTemplate,
                                         ReferentialProperties properties,
                                         ReferentialUrlProperties urls) {
        return new RestEmailReferentialClient(
                referentialRestTemplate, properties, urls.getEmailFromAddress());
    }
}
