package com.sg.bootstrap.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.bootstrap.config.RegistrationProperties;
import com.sg.bootstrap.config.RegistrationProperties.BusinessConfig;
import com.sg.bootstrap.config.RegistrationProperties.FeeCategoryConfig;
import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.port.out.AlertRoutingPolicy.Route;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * How the three alert settings resolve across the three scopes.
 *
 * <p>Each resolves independently: a business overriding only its recipients keeps the inherited
 * {@code enabled} and subject prefix. Resolving them together would mean any override forced a
 * restatement of the other two, and the first one somebody forgot would silently revert to the
 * service default.
 */
class ConfiguredAlertRoutingPolicyTest {

  private final RegistrationProperties props = new RegistrationProperties();

  private BusinessConfig business(Business business) {
    return props.getBusinesses().computeIfAbsent(business, b -> new BusinessConfig());
  }

  private FeeCategoryConfig feeCategory(Business business, String name) {
    return business(business).getFeeCategories().computeIfAbsent(name, k -> new FeeCategoryConfig());
  }

  private Route route(Business business, String feeCategory) {
    return new ConfiguredAlertRoutingPolicy(props).routeFor(business, feeCategory);
  }

  // ── Inheritance ───────────────────────────────────────────────────────────

  @Nested
  @DisplayName("resolution order")
  class Resolution {

    @Test
    @DisplayName("with nothing configured, the service-wide block answers")
    void fallsBackToService() {
      props.getAlert().setRecipients(List.of("ops@example.com"));
      props.getAlert().setSubjectPrefix("[invoice-service]");

      Route route = route(Business.MARK, "CUSTODY");

      assertTrue(route.shouldSend());
      assertEquals(List.of("ops@example.com"), route.recipients());
      assertEquals("[invoice-service]", route.subjectPrefix());
    }

    @Test
    @DisplayName("a business overrides the service-wide block")
    void businessOverridesService() {
      props.getAlert().setRecipients(List.of("ops@example.com"));
      business(Business.MARK).getAlert().setRecipients(List.of("mark@example.com"));

      assertEquals(List.of("mark@example.com"), route(Business.MARK, "CUSTODY").recipients());
      assertEquals(List.of("ops@example.com"), route(Business.SGSS, "CUSTODY").recipients(),
          "a business that said nothing keeps the default");
    }

    @Test
    @DisplayName("a fee category overrides its business")
    void feeCategoryOverridesBusiness() {
      props.getAlert().setRecipients(List.of("ops@example.com"));
      business(Business.MARK).getAlert().setRecipients(List.of("mark@example.com"));
      feeCategory(Business.MARK, "BROKERAGE").getAlert()
          .setRecipients(List.of("brokerage@example.com"));

      assertEquals(List.of("brokerage@example.com"),
          route(Business.MARK, "BROKERAGE").recipients());
      assertEquals(List.of("mark@example.com"), route(Business.MARK, "CUSTODY").recipients(),
          "the other fee categories are untouched by one category's override");
    }

    @Test
    @DisplayName("the three settings resolve independently of one another")
    void settingsResolveIndependently() {
      props.getAlert().setRecipients(List.of("ops@example.com"));
      props.getAlert().setSubjectPrefix("[invoice-service]");
      props.getAlert().setEnabled(true);

      // Only the recipients are overridden. The prefix and the switch must survive.
      business(Business.MARK).getAlert().setRecipients(List.of("mark@example.com"));

      Route route = route(Business.MARK, "CUSTODY");
      assertEquals(List.of("mark@example.com"), route.recipients());
      assertEquals("[invoice-service]", route.subjectPrefix(),
          "overriding one setting must not silently revert the other two");
      assertTrue(route.enabled());
    }
  }

  // ── Switching off ─────────────────────────────────────────────────────────

  @Nested
  @DisplayName("enabling and disabling")
  class Switching {

    @Test
    @DisplayName("an unconfigured scope alerts by default")
    void defaultsToOn() {
      // Going silent by default would hide failures in exactly the environments nobody has
      // finished configuring, which is where they matter most.
      props.getAlert().setRecipients(List.of("ops@example.com"));
      assertTrue(route(Business.GTPS, "ANY").shouldSend());
    }

    @Test
    @DisplayName("a fee category can go quiet while its business keeps alerting")
    void feeCategoryCanGoQuiet() {
      props.getAlert().setRecipients(List.of("ops@example.com"));
      feeCategory(Business.SGSS, "INTERNAL_RECHARGE").getAlert().setEnabled(false);

      assertFalse(route(Business.SGSS, "INTERNAL_RECHARGE").shouldSend());
      assertTrue(route(Business.SGSS, "CUSTODY").shouldSend());
    }

    @Test
    @DisplayName("a business can go quiet, and one of its categories can opt back in")
    void businessQuietWithCategoryOptIn() {
      props.getAlert().setRecipients(List.of("ops@example.com"));
      business(Business.GLBA).getAlert().setEnabled(false);
      feeCategory(Business.GLBA, "CUSTODY").getAlert().setEnabled(true);

      assertFalse(route(Business.GLBA, "OTHER").shouldSend());
      assertTrue(route(Business.GLBA, "CUSTODY").shouldSend(),
          "an explicit true at the narrower scope wins over the business's false");
    }

    @Test
    @DisplayName("enabled with no recipients anywhere still does not send")
    void enabledButNobodyToTell() {
      props.getAlert().setEnabled(true);
      assertFalse(route(Business.MARK, "CUSTODY").shouldSend());
    }

    @Test
    @DisplayName("an empty recipient list reads as 'did not say', not as 'send to nobody'")
    void emptyListInherits() {
      props.getAlert().setRecipients(List.of("ops@example.com"));
      business(Business.MARK).getAlert().setRecipients(List.of());

      assertEquals(List.of("ops@example.com"), route(Business.MARK, "CUSTODY").recipients(),
          "declaring the key and leaving it empty means undecided, not silenced");
    }
  }

  // ── Matching ──────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("scope matching")
  class Matching {

    @Test
    @DisplayName("fee categories match case- and whitespace-insensitively")
    void feeCategoryMatchingIsLenient() {
      // The value arrives from the endpoint marker as whatever the sender typed.
      props.getAlert().setRecipients(List.of("ops@example.com"));
      feeCategory(Business.MARK, "CUSTODY").getAlert().setRecipients(List.of("cus@example.com"));

      assertEquals(List.of("cus@example.com"), route(Business.MARK, "custody").recipients());
      assertEquals(List.of("cus@example.com"), route(Business.MARK, "  CuStOdY  ").recipients());
    }

    @Test
    @DisplayName("an unresolved business or fee category falls straight to the default")
    void unresolvedScopesFallBack() {
      props.getAlert().setRecipients(List.of("ops@example.com"));
      business(Business.MARK).getAlert().setRecipients(List.of("mark@example.com"));

      assertEquals(List.of("ops@example.com"), route(null, "CUSTODY").recipients(),
          "mapping can fail before the business is known, and that alert still has to land");
      assertEquals(List.of("mark@example.com"), route(Business.MARK, null).recipients());
      assertEquals(List.of("mark@example.com"), route(Business.MARK, "   ").recipients());
    }

    @Test
    @DisplayName("an unknown fee category uses its business's settings")
    void unknownFeeCategoryUsesBusiness() {
      business(Business.MARK).getAlert().setRecipients(List.of("mark@example.com"));
      feeCategory(Business.MARK, "CUSTODY").getAlert().setRecipients(List.of("cus@example.com"));

      assertEquals(List.of("mark@example.com"),
          route(Business.MARK, "SOMETHING_NEW").recipients());
    }

    @Test
    @DisplayName("with nothing configured at all there is a usable prefix and no recipients")
    void emptyConfiguration() {
      Route route = route(Business.MARK, "CUSTODY");
      assertEquals("[invoice-service]", route.subjectPrefix());
      assertEquals(List.of(), route.recipients());
      assertFalse(route.shouldSend());
    }

    @Test
    @DisplayName("a fee category's own prefix and recipients win over both wider scopes")
    void feeCategoryWinsOnEverySetting() {
      props.getAlert().setRecipients(List.of("ops@example.com"));
      props.getAlert().setSubjectPrefix("[invoice-service]");
      business(Business.MARK).getAlert().setRecipients(List.of("mark@example.com"));
      business(Business.MARK).getAlert().setSubjectPrefix("[MARK]");
      feeCategory(Business.MARK, "BROKERAGE").getAlert()
          .setRecipients(List.of("brokerage@example.com"));
      feeCategory(Business.MARK, "BROKERAGE").getAlert().setSubjectPrefix("[MARK][BROKERAGE]");

      Route route = route(Business.MARK, "BROKERAGE");
      assertEquals(List.of("brokerage@example.com"), route.recipients());
      assertEquals("[MARK][BROKERAGE]", route.subjectPrefix(),
          "a filter routing on the prefix needs the narrowest scope's answer");
    }

    @Test
    @DisplayName("a fee category with a blank prefix falls back to its business's")
    void blankFeeCategoryPrefixFallsBackToBusiness() {
      // Declaring the key and leaving it blank is not a request for a blank subject line — it
      // means the category has not chosen one, so the business's answer still applies.
      props.getAlert().setSubjectPrefix("[invoice-service]");
      business(Business.MARK).getAlert().setSubjectPrefix("[MARK]");
      feeCategory(Business.MARK, "CUSTODY").getAlert().setSubjectPrefix("   ");

      assertEquals("[MARK]", route(Business.MARK, "CUSTODY").subjectPrefix());
    }

    @Test
    @DisplayName("a blank prefix at every scope leaves the built-in default")
    void allBlankPrefixesFallToTheDefault() {
      props.getAlert().setSubjectPrefix("  ");
      business(Business.MARK).getAlert().setSubjectPrefix("");

      // Never an empty prefix: the subject would begin with a space and no filter would match.
      assertEquals("[invoice-service]", route(Business.MARK, "CUSTODY").subjectPrefix());
    }

    @Test
    @DisplayName("an empty list at every scope resolves to no recipients, not to null")
    void allEmptyRecipientsResolveToEmpty() {
      business(Business.MARK).getAlert().setRecipients(List.of());
      feeCategory(Business.MARK, "CUSTODY").getAlert().setRecipients(List.of());

      Route route = route(Business.MARK, "CUSTODY");
      assertEquals(List.of(), route.recipients());
      assertFalse(route.shouldSend());
    }

    @Test
    @DisplayName("a blank subject prefix is ignored in favour of the next scope")
    void blankPrefixInherits() {
      props.getAlert().setSubjectPrefix("[invoice-service]");
      business(Business.MARK).getAlert().setSubjectPrefix("   ");

      assertEquals("[invoice-service]", route(Business.MARK, "CUSTODY").subjectPrefix());
    }
  }

  @Test
  @DisplayName("the properties are mandatory")
  void propertiesAreMandatory() {
    assertThrows(NullPointerException.class, () -> new ConfiguredAlertRoutingPolicy(null));
  }

  @Test
  @DisplayName("rule ids are lower-cased so config casing cannot silently disable a rule")
  void ruleIdsAreNormalised() {
    BusinessConfig cfg = business(Business.MARK);
    cfg.setRules(Map.of("DUPLICATE-INVOICE", true, "attachment-present", true,
        "line-items-present", false));

    // Spring's relaxed binding will hand back whatever casing the file used. A rule id that
    // does not match lower-case kebab matches no rule, which reads as "disabled" and says so
    // nowhere.
    assertEquals(java.util.Set.of("duplicate-invoice", "attachment-present"),
        cfg.enabledRuleIds());

    cfg.setRules(null);
    assertEquals(java.util.Set.of(), cfg.enabledRuleIds(),
        "an absent block is an empty set, not a failure");
  }

  @Test
  @DisplayName("a fee category distinguishes 'no rule block' from 'an empty one'")
  void feeCategoryRuleOverrideIsExplicit() {
    FeeCategoryConfig cfg = feeCategory(Business.MARK, "CUSTODY");

    assertFalse(cfg.overridesRules(), "absent means inherit the business's set");
    assertEquals(java.util.Set.of(), cfg.enabledRuleIds());

    cfg.setRules(Map.of());
    assertTrue(cfg.overridesRules(),
        "an empty block means run nothing, which is how a category is exempted entirely");
    assertEquals(java.util.Set.of(), cfg.enabledRuleIds());

    cfg.setRules(Map.of("duplicate-invoice", true));
    assertTrue(cfg.overridesRules());
    assertEquals(java.util.Set.of("duplicate-invoice"), cfg.enabledRuleIds());
  }
}
