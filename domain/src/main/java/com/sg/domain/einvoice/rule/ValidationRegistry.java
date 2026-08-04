package com.sg.domain.einvoice.rule;

import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.rule.einvoice.ValidationRule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable per-{@link Business} rule catalogue.
 *
 * <p>Wires which {@link ValidationRule}s the orchestrator runs for a given business. Each
 * business gets its own ordered list; rules can be shared across businesses (that's the
 * common case) or restricted to one.
 *
 * <p><b>Building.</b> Use {@link Builder}. The app-module composition root reads
 * {@code invoice.service.registration.businesses.<BIZ>.rules.<rule-id>=true|false} and calls
 * {@link Builder#add(Business, ValidationRule)} for enabled rules. A rule not added for a
 * business is simply absent — no runtime toggle in the rule itself, which keeps rule bodies
 * pure.
 *
 * <p><b>Unknown business.</b> {@link #rulesFor(Business)} returns an empty list when the
 * business isn't configured. Deliberate: onboarding a new business shouldn't accidentally
 * reject every invoice. Ops sees a REGISTERED row and a "business not configured" log line
 * (not an alert — the row is fine).
 */
public final class ValidationRegistry {

  private final Map<Business, List<ValidationRule>> rulesByBusiness;

  private ValidationRegistry(Map<Business, List<ValidationRule>> rulesByBusiness) {
    Map<Business, List<ValidationRule>> copy = new EnumMap<>(Business.class);
    for (Map.Entry<Business, List<ValidationRule>> e : rulesByBusiness.entrySet()) {
      copy.put(e.getKey(), List.copyOf(e.getValue()));
    }
    this.rulesByBusiness = Collections.unmodifiableMap(copy);
  }

  public List<ValidationRule> rulesFor(Business business) {
    if (business == null) return List.of();
    return rulesByBusiness.getOrDefault(business, List.of());
  }

  public Set<Business> configuredBusinesses() {
    return rulesByBusiness.keySet();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private final Map<Business, List<ValidationRule>> rules = new EnumMap<>(Business.class);

    private Builder() {}

    /** Register a rule for one business. Order of {@code add} calls is preserved. */
    public Builder add(Business business, ValidationRule rule) {
      Objects.requireNonNull(business, "business");
      Objects.requireNonNull(rule, "rule");
      rules.computeIfAbsent(business, k -> new ArrayList<>()).add(rule);
      return this;
    }

    /** Register a rule for every listed business in one call. */
    public Builder addForAll(Iterable<Business> businesses, ValidationRule rule) {
      for (Business b : businesses) add(b, rule);
      return this;
    }

    public ValidationRegistry build() {
      return new ValidationRegistry(rules);
    }
  }
}
