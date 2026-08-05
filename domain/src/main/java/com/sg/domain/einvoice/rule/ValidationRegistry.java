package com.sg.domain.einvoice.rule;

import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.rule.einvoice.ValidationRule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which rules run, for a given business and fee category.
 *
 * <p><b>Two levels of scope, most specific first.</b> A rule set is configured per business and
 * may be overridden for one fee category within it. Asking for {@code (MARK, CUSTODY)} returns
 * CUSTODY's set if one is configured and MARK's otherwise.
 *
 * <p>The fee category matters because one business handles work with genuinely different
 * paperwork. Requiring a trade file across all of MARK would refuse every custody invoice, which
 * never has one; requiring an attachment across all of SGSS would refuse the internal recharges
 * that legitimately arrive bare. Before this the only way to express either was to turn the rule
 * off for the whole business and lose it where it mattered.
 *
 * <p><b>An override replaces, it does not merge.</b> A fee category that configures its own set
 * gets exactly that set. Merging would make "turn this rule off for CUSTODY" inexpressible — the
 * business-level entry would keep switching it back on while the config read as though it were
 * disabled.
 *
 * <p>Fee categories match case-insensitively. They arrive from the endpoint marker as whatever
 * the sender typed, and a config keyed {@code CUSTODY} should not be missed by a marker that
 * said {@code custody}.
 */
public final class ValidationRegistry {

  /** Business-level sets, used when no fee category overrides them. */
  private final Map<Business, List<ValidationRule>> byBusiness;

  /** {@code BUSINESS\0FEECATEGORY} → the set that replaces the business one. */
  private final Map<String, List<ValidationRule>> byFeeCategory;

  private ValidationRegistry(Map<Business, List<ValidationRule>> byBusiness,
                             Map<String, List<ValidationRule>> byFeeCategory) {
    Map<Business, List<ValidationRule>> business = new EnumMap<>(Business.class);
    byBusiness.forEach((k, v) -> business.put(k, List.copyOf(v)));
    this.byBusiness = Collections.unmodifiableMap(business);

    Map<String, List<ValidationRule>> fee = new HashMap<>();
    byFeeCategory.forEach((k, v) -> fee.put(k, List.copyOf(v)));
    this.byFeeCategory = Collections.unmodifiableMap(fee);
  }

  /**
   * The rules to run.
   *
   * @param business    the resolved business, or {@code null} when the marker yielded none
   * @param feeCategory the resolved fee category, or {@code null}
   * @return the fee category's set when configured, else the business's, else empty. An
   *         unresolved business runs nothing: there is no sensible default set, and guessing
   *         would apply one business's policy to another's invoice.
   */
  public List<ValidationRule> rulesFor(Business business, String feeCategory) {
    if (business == null) {
      return List.of();
    }
    if (feeCategory != null && !feeCategory.isBlank()) {
      List<ValidationRule> scoped = byFeeCategory.get(key(business, feeCategory));
      if (scoped != null) {
        return scoped;
      }
    }
    return byBusiness.getOrDefault(business, List.of());
  }

  private static String key(Business business, String feeCategory) {
    return business.name() + '\0' + feeCategory.trim().toUpperCase(Locale.ROOT);
  }

  /** Every business with a configured rule set. */
  public Set<Business> configuredBusinesses() {
    return byBusiness.keySet();
  }

  /** The fee categories overriding the given business, upper-cased. */
  public Set<String> configuredFeeCategories(Business business) {
    if (business == null) {
      return Set.of();
    }
    String prefix = business.name() + '\0';
    Set<String> out = new LinkedHashSet<>();
    for (String k : byFeeCategory.keySet()) {
      if (k.startsWith(prefix)) {
        out.add(k.substring(prefix.length()));
      }
    }
    return Collections.unmodifiableSet(out);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private final Map<Business, List<ValidationRule>> byBusiness = new EnumMap<>(Business.class);
    private final Map<String, List<ValidationRule>> byFeeCategory = new HashMap<>();

    private Builder() {}

    /** Register a rule for a business. Order of {@code add} calls is preserved. */
    public Builder add(Business business, ValidationRule rule) {
      Objects.requireNonNull(business, "business");
      Objects.requireNonNull(rule, "rule");
      byBusiness.computeIfAbsent(business, k -> new ArrayList<>()).add(rule);
      return this;
    }

    /**
     * Register a rule for one fee category within a business, replacing the business set for it.
     */
    public Builder add(Business business, String feeCategory, ValidationRule rule) {
      Objects.requireNonNull(rule, "rule");
      scope(business, feeCategory).add(rule);
      return this;
    }

    /**
     * Declare a fee category's set without putting anything in it.
     *
     * <p>Needed because empty and absent mean different things: absent falls back to the
     * business, empty runs nothing. Without this there would be no way to switch every rule off
     * for a single fee category while leaving the business's own set alone.
     */
    public Builder addFeeCategoryScope(Business business, String feeCategory) {
      scope(business, feeCategory);
      return this;
    }

    /** Register a rule for every listed business in one call. */
    public Builder addForAll(Iterable<Business> businesses, ValidationRule rule) {
      for (Business b : businesses) {
        add(b, rule);
      }
      return this;
    }

    public ValidationRegistry build() {
      return new ValidationRegistry(byBusiness, byFeeCategory);
    }

    private List<ValidationRule> scope(Business business, String feeCategory) {
      Objects.requireNonNull(business, "business");
      Objects.requireNonNull(feeCategory, "feeCategory");
      return byFeeCategory.computeIfAbsent(key(business, feeCategory), k -> new ArrayList<>());
    }
  }
}
