package com.sg.bootstrap.config;

import com.sg.domaininterface.model.einvoice.Business;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code invoice.service.registration.*} — per-business, per-fee-category configuration.
 *
 * <p><b>Two levels of scope, most specific first,</b> for both the validation rules and the
 * alerting. A fee category inside a business overrides that business, which overrides the
 * service-wide default. One resolution order for both, so there is a single rule to remember:
 *
 * <pre>
 * invoice.service.registration:
 *   alert:                                # service-wide fallback
 *     enabled: true
 *     recipients: [ops&#64;example.com]
 *     subject-prefix: "[invoice-service]"
 *   businesses:
 *     MARK:
 *       rules:                            # applies to every MARK fee category ...
 *         duplicate-invoice: true
 *         attachment-present: true
 *       alert:
 *         recipients: [mark-ops&#64;example.com]
 *         subject-prefix: "[MARK]"
 *       fee-categories:
 *         CUSTODY:
 *           rules:                        # ... unless the fee category says otherwise
 *             duplicate-invoice: true     # custody invoices never carry an attachment
 *           alert:
 *             enabled: false              # and nobody wants to hear about them
 * </pre>
 *
 * <p><b>A fee-category rule block replaces the business one; it does not merge.</b> Merging
 * would make "switch this rule off for CUSTODY" inexpressible — the business entry would keep
 * turning it back on while the config read as though it were disabled.
 */
@ConfigurationProperties(prefix = "invoice.service.registration")
public final class RegistrationProperties {

  private final Alert alert = new Alert();
  private final Map<Business, BusinessConfig> businesses = new EnumMap<>(Business.class);

  public Alert getAlert() {
    return alert;
  }

  public Map<Business, BusinessConfig> getBusinesses() {
    return businesses;
  }

  /**
   * Where alerts go for one scope.
   *
   * <p>{@code enabled} is a {@link Boolean} rather than a primitive on purpose: null means "this
   * scope did not say", which is what lets a fee category inherit its business's answer instead
   * of silently defaulting to false and going quiet.
   */
  public static class Alert {
    private Boolean enabled;
    private List<String> recipients;
    private String subjectPrefix;

    public Boolean getEnabled() { return enabled; }

    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public List<String> getRecipients() { return recipients; }

    public void setRecipients(List<String> recipients) { this.recipients = recipients; }

    public String getSubjectPrefix() { return subjectPrefix; }

    public void setSubjectPrefix(String subjectPrefix) { this.subjectPrefix = subjectPrefix; }
  }

  /** One business: its rules, its alert routing, and any fee categories that differ. */
  public static final class BusinessConfig {
    private Map<String, Boolean> rules = Map.of();
    private final Alert alert = new Alert();
    private final Map<String, FeeCategoryConfig> feeCategories = new LinkedHashMap<>();

    public Map<String, Boolean> getRules() { return rules; }

    public void setRules(Map<String, Boolean> rules) {
      this.rules = rules == null ? Map.of() : rules;
    }

    public Alert getAlert() { return alert; }

    public Map<String, FeeCategoryConfig> getFeeCategories() { return feeCategories; }

    public Set<String> enabledRuleIds() {
      return enabled(rules);
    }
  }

  /** One fee category within a business. Both blocks are optional and independent. */
  public static final class FeeCategoryConfig {
    private Map<String, Boolean> rules;
    private final Alert alert = new Alert();

    /**
     * @return null when this fee category did not configure rules at all, meaning "use the
     *         business's set". An empty map means "run nothing" — the two are different, and
     *         collapsing them would remove the only way to disable everything for one category.
     */
    public Map<String, Boolean> getRules() { return rules; }

    public void setRules(Map<String, Boolean> rules) { this.rules = rules; }

    public boolean overridesRules() { return rules != null; }

    public Alert getAlert() { return alert; }

    public Set<String> enabledRuleIds() {
      return rules == null ? Set.of() : enabled(rules);
    }
  }

  /**
   * The keys mapped to true.
   *
   * <p>Rule ids are lower-cased on the way out. They are kebab-case in code, and Spring's relaxed
   * binding will happily hand back {@code DUPLICATE-INVOICE} from a differently-cased key — which
   * would match no rule and disable it without saying so.
   */
  private static Set<String> enabled(Map<String, Boolean> rules) {
    return rules.entrySet().stream()
        .filter(e -> Boolean.TRUE.equals(e.getValue()))
        .map(e -> e.getKey().toLowerCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());
  }
}
