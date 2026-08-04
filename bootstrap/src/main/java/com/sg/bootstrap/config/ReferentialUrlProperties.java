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

    public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
    public void setConnectTimeoutMillis(int v) { this.connectTimeoutMillis = v; }

    public int getReadTimeoutMillis() { return readTimeoutMillis; }
    public void setReadTimeoutMillis(int v) { this.readTimeoutMillis = v; }
}
