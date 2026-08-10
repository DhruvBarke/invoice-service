package com.sg.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code invoice.service.referential.*} — where the referentials live, and how long to wait.
 *
 * <p>No defaults for the URLs. A default would let the service start pointing at something that
 * does not exist and fail on the first invoice instead of at boot, which is the more expensive
 * time to find out.
 */
@ConfigurationProperties(prefix = "invoice.service.referential")
public class ReferentialUrlProperties {

    private String partyBaseUrl;
    private String feeCategoryBaseUrl;
    private String sgDocBaseUrl;
    private String emailBaseUrl;

    /**
     * Serves FX rates, the business calendar and settlement instructions.
     *
     * <p>One property for the three because the manual registration path reaches all of them
     * through a single {@code referentialServiceApi}. It may hold the same value as
     * {@code party-base-url}; that is a deployment question, and defaulting it to one would hide
     * the day they diverge.
     */
    private String commonBaseUrl;

    /**
     * The sender every alert is posted from.
     *
     * <p>Configuration rather than a constant: it differs per environment, and a production
     * alert arriving from a test mailbox is one people learn to ignore.
     */
    private String emailFromAddress;

    /** Long enough for a TLS handshake on a cold connection, short enough to fail a dead host. */
    private int connectTimeoutMillis = 3_000;

    /**
     * The referential answers in tens of milliseconds when healthy. Ten seconds is generous
     * enough to ride out a slow moment and short enough that a hung dependency does not become
     * a hung invoice service.
     */
    private int readTimeoutMillis = 10_000;

    public String getPartyBaseUrl() { return partyBaseUrl; }
    public void setPartyBaseUrl(String partyBaseUrl) { this.partyBaseUrl = partyBaseUrl; }

    public String getFeeCategoryBaseUrl() { return feeCategoryBaseUrl; }
    public void setFeeCategoryBaseUrl(String v) { this.feeCategoryBaseUrl = v; }

    public String getSgDocBaseUrl() { return sgDocBaseUrl; }
    public void setSgDocBaseUrl(String sgDocBaseUrl) { this.sgDocBaseUrl = sgDocBaseUrl; }

    public String getEmailBaseUrl() { return emailBaseUrl; }
    public void setEmailBaseUrl(String emailBaseUrl) { this.emailBaseUrl = emailBaseUrl; }

    public String getCommonBaseUrl() { return commonBaseUrl; }
    public void setCommonBaseUrl(String commonBaseUrl) { this.commonBaseUrl = commonBaseUrl; }

    public String getEmailFromAddress() { return emailFromAddress; }
    public void setEmailFromAddress(String v) { this.emailFromAddress = v; }

    public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
    public void setConnectTimeoutMillis(int v) { this.connectTimeoutMillis = v; }

    public int getReadTimeoutMillis() { return readTimeoutMillis; }
    public void setReadTimeoutMillis(int v) { this.readTimeoutMillis = v; }
}
