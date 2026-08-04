package com.sg.bootstrap.config;

import com.sg.domaininterface.model.einvoice.Business;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code invoice.service.registration.*} — per-business rule toggles + alert wiring.
 *
 * <p>YAML shape (see {@code application-invoice-service.yml}):
 * <pre>
 * invoice:
 *   service:
 *     registration:
 *       alert:
 *         recipients: [ops@sg.com, invoice-alerts@sg.com]
 *         subject-prefix: "[invoice-service]"
 *       businesses:
 *         MARK:
 *           rules:
 *             duplicate-invoice: true
 *             attachment-present: true
 *             brokerage-trade-file: true
 *             line-items-present: true
 *         SGSS:
 *           rules:
 *             duplicate-invoice: true
 *             attachment-present: true
 *             # brokerage-trade-file omitted → defaults to disabled for this business
 *             line-items-present: true
 * </pre>
 *
 * <p>A rule not mentioned for a business defaults to disabled. A business absent from the
 * config entirely gets an empty rule set — the row will be REGISTERED unless the mapper
 * itself surfaces an error.
 */
@ConfigurationProperties(prefix = "invoice.service.registration")
public final class RegistrationProperties {

  private final Alert alert = new Alert();
  private final Map<Business, BusinessRules> businesses = new EnumMap<>(Business.class);

  public Alert getAlert() { return alert; }
  public Map<Business, BusinessRules> getBusinesses() { return businesses; }

  public static final class Alert {
    private List<String> recipients = List.of();
    private String subjectPrefix = "[invoice-service]";

    public List<String> getRecipients() { return recipients; }
    public void setRecipients(List<String> recipients) { this.recipients = recipients; }
    public String getSubjectPrefix() { return subjectPrefix; }
    public void setSubjectPrefix(String subjectPrefix) { this.subjectPrefix = subjectPrefix; }
  }

  public static final class BusinessRules {
    private Map<String, Boolean> rules = Map.of();

    public Map<String, Boolean> getRules() { return rules; }
    public void setRules(Map<String, Boolean> rules) { this.rules = rules == null ? Map.of() : rules; }

    /** Rule ids explicitly enabled for this business. */
    public Set<String> enabledRuleIds() {
      return rules.entrySet().stream()
          .filter(e -> Boolean.TRUE.equals(e.getValue()))
          .map(Map.Entry::getKey)
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
  }
}
