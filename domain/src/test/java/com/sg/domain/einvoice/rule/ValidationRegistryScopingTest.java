package com.sg.domain.einvoice.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.model.einvoice.error.MappingError;
import com.sg.domaininterface.rule.einvoice.ValidationContext;
import com.sg.domaininterface.rule.einvoice.ValidationRule;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Rule sets resolved by business and fee category.
 *
 * <p>One business handles work with genuinely different paperwork. Requiring a trade file across
 * all of MARK refuses every custody invoice, which never has one; requiring an attachment across
 * all of SGSS refuses the internal recharges that legitimately arrive bare. The fee-category
 * scope exists so neither costs the rule everywhere else.
 */
class ValidationRegistryScopingTest {

  /** A rule identified only by its name, so a set can be compared by id. */
  private record NamedRule(String name) implements ValidationRule {
    @Override public List<MappingError> check(ValidationContext ctx) { return List.of(); }
    @Override public String id() { return name; }
  }

  private static final ValidationRule DUPLICATE = new NamedRule("duplicate-invoice");
  private static final ValidationRule ATTACHMENT = new NamedRule("attachment-present");
  private static final ValidationRule TRADE_FILE = new NamedRule("brokerage-trade-file");

  private static Set<String> idsOf(List<ValidationRule> rules) {
    return rules.stream().map(ValidationRule::id).collect(java.util.stream.Collectors.toSet());
  }

  @Nested
  @DisplayName("resolution")
  class Resolution {

    @Test
    @DisplayName("a fee category with no set of its own inherits the business's")
    void inheritsFromBusiness() {
      ValidationRegistry registry = ValidationRegistry.builder()
          .add(Business.MARK, DUPLICATE)
          .add(Business.MARK, ATTACHMENT)
          .build();

      assertEquals(Set.of("duplicate-invoice", "attachment-present"),
          idsOf(registry.rulesFor(Business.MARK, "ANYTHING")));
      assertEquals(Set.of("duplicate-invoice", "attachment-present"),
          idsOf(registry.rulesFor(Business.MARK, null)));
    }

    @Test
    @DisplayName("a fee category's set REPLACES the business's rather than merging")
    void overrideReplaces() {
      // Merging would make "switch attachment-present off for CUSTODY" inexpressible: the
      // business entry would keep turning it back on while the config read as disabled.
      ValidationRegistry registry = ValidationRegistry.builder()
          .add(Business.MARK, DUPLICATE)
          .add(Business.MARK, ATTACHMENT)
          .add(Business.MARK, "CUSTODY", DUPLICATE)
          .build();

      assertEquals(Set.of("duplicate-invoice"),
          idsOf(registry.rulesFor(Business.MARK, "CUSTODY")));
      assertEquals(Set.of("duplicate-invoice", "attachment-present"),
          idsOf(registry.rulesFor(Business.MARK, "BROKERAGE")),
          "the other categories are untouched by one category's override");
    }

    @Test
    @DisplayName("a declared but empty fee-category set runs nothing")
    void emptyScopeRunsNothing() {
      // Empty and absent are different: absent inherits, empty exempts. Without the distinction
      // there is no way to switch every rule off for a single fee category.
      ValidationRegistry registry = ValidationRegistry.builder()
          .add(Business.SGSS, DUPLICATE)
          .add(Business.SGSS, ATTACHMENT)
          .addFeeCategoryScope(Business.SGSS, "INTERNAL_RECHARGE")
          .build();

      assertTrue(registry.rulesFor(Business.SGSS, "INTERNAL_RECHARGE").isEmpty());
      assertEquals(2, registry.rulesFor(Business.SGSS, "CUSTODY").size());
    }

    @Test
    @DisplayName("fee categories match case- and whitespace-insensitively")
    void matchingIsLenient() {
      // The value arrives from the endpoint marker as whatever the sender typed.
      ValidationRegistry registry = ValidationRegistry.builder()
          .add(Business.MARK, ATTACHMENT)
          .add(Business.MARK, "CUSTODY", DUPLICATE)
          .build();

      assertEquals(Set.of("duplicate-invoice"),
          idsOf(registry.rulesFor(Business.MARK, "custody")));
      assertEquals(Set.of("duplicate-invoice"),
          idsOf(registry.rulesFor(Business.MARK, "  CuStOdY ")));
    }

    @Test
    @DisplayName("an unresolved business runs nothing at all")
    void unresolvedBusinessRunsNothing() {
      // There is no sensible default set, and guessing one applies another business's policy to
      // an invoice that was never theirs.
      ValidationRegistry registry = ValidationRegistry.builder()
          .add(Business.MARK, DUPLICATE)
          .build();

      assertTrue(registry.rulesFor(null, "CUSTODY").isEmpty());
      assertTrue(registry.rulesFor(null, null).isEmpty());
      assertTrue(registry.rulesFor(Business.GTPS, "CUSTODY").isEmpty(),
          "onboarding a business must not start rejecting its invoices by default");
    }

    @Test
    @DisplayName("a blank fee category resolves to the business set, not to an empty one")
    void blankFeeCategoryFallsBack() {
      ValidationRegistry registry = ValidationRegistry.builder()
          .add(Business.MARK, DUPLICATE)
          .build();

      assertEquals(1, registry.rulesFor(Business.MARK, "   ").size());
    }
  }

  @Nested
  @DisplayName("the builder")
  class Building {

    @Test
    @DisplayName("registration order is preserved within a scope")
    void orderIsPreserved() {
      // Rules report in the order they run, and the outcome's comment is taken from the first
      // error. A reordering would silently change which failure the sender is told about.
      ValidationRegistry registry = ValidationRegistry.builder()
          .add(Business.MARK, DUPLICATE)
          .add(Business.MARK, ATTACHMENT)
          .add(Business.MARK, TRADE_FILE)
          .build();

      assertEquals(List.of("duplicate-invoice", "attachment-present", "brokerage-trade-file"),
          registry.rulesFor(Business.MARK, null).stream().map(ValidationRule::id).toList());
    }

    @Test
    @DisplayName("addForAll registers one rule against several businesses")
    void addForAll() {
      ValidationRegistry registry = ValidationRegistry.builder()
          .addForAll(List.of(Business.MARK, Business.SGSS), DUPLICATE)
          .build();

      assertEquals(Set.of("duplicate-invoice"), idsOf(registry.rulesFor(Business.MARK, null)));
      assertEquals(Set.of("duplicate-invoice"), idsOf(registry.rulesFor(Business.SGSS, null)));
    }

    @Test
    @DisplayName("the configured scopes are reportable")
    void configuredScopesAreVisible() {
      ValidationRegistry registry = ValidationRegistry.builder()
          .add(Business.MARK, DUPLICATE)
          .add(Business.MARK, "CUSTODY", DUPLICATE)
          .add(Business.MARK, "brokerage", TRADE_FILE)
          .add(Business.SGSS, DUPLICATE)
          .build();

      assertEquals(Set.of(Business.MARK, Business.SGSS), registry.configuredBusinesses());
      assertEquals(Set.of("CUSTODY", "BROKERAGE"),
          registry.configuredFeeCategories(Business.MARK),
          "reported upper-cased, matching how they are keyed");
      assertEquals(Set.of(), registry.configuredFeeCategories(Business.SGSS));
      assertEquals(Set.of(), registry.configuredFeeCategories(null));
    }

    @Test
    @DisplayName("nulls are rejected at registration, not at lookup")
    void nullsAreRejectedEarly() {
      ValidationRegistry.Builder builder = ValidationRegistry.builder();

      assertThrows(NullPointerException.class, () -> builder.add(null, DUPLICATE));
      assertThrows(NullPointerException.class, () -> builder.add(Business.MARK, (ValidationRule) null));
      assertThrows(NullPointerException.class, () -> builder.add(Business.MARK, "CUSTODY", null));
      assertThrows(NullPointerException.class, () -> builder.add(null, "CUSTODY", DUPLICATE));
      assertThrows(NullPointerException.class,
          () -> builder.addFeeCategoryScope(Business.MARK, null));
    }

    @Test
    @DisplayName("the returned lists are immutable")
    void listsAreImmutable() {
      ValidationRegistry registry = ValidationRegistry.builder()
          .add(Business.MARK, DUPLICATE)
          .build();

      assertThrows(UnsupportedOperationException.class,
          () -> registry.rulesFor(Business.MARK, null).add(ATTACHMENT));
    }
  }
}
