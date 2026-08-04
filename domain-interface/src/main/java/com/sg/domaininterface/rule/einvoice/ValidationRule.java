package com.sg.domaininterface.rule.einvoice;

import com.sg.domaininterface.model.einvoice.error.MappingError;
import java.util.List;

/**
 * A single business-configurable check on an incoming e-invoice registration.
 *
 * <p>Contract: rules are stateless, return quickly, and never throw. A rule that discovers a
 * failure returns one or more {@link MappingError}s; a clean pass returns an empty list.
 *
 * <p><b>Composition, not inheritance.</b> Rules do not know about each other. The orchestrator
 * runs every rule the {@link ValidationRegistry} yields for the current
 * {@link com.sg.domaininterface.model.einvoice.Business} and accumulates the union of
 * their outputs. Ordering is only used to keep the alert email stable across runs.
 *
 * <p><b>Adding a new rule.</b> Implement this interface, give it a stable {@link #id()}
 * (used in the per-business YAML config for enable/disable), and register it in the
 * {@link ValidationRegistry.Builder}. No changes needed to the orchestrator or any other rule.
 */
@FunctionalInterface
public interface ValidationRule {

  /**
   * @param ctx snapshot after mapping; some fields may be null when the mapping upstream
   *            failed — implementations must tolerate that
   * @return one entry per distinct failure this rule detected; empty on pass
   */
  List<MappingError> check(ValidationContext ctx);

  /**
   * Stable identifier used in configuration (e.g.
   * {@code invoice.service.registration.businesses.MARK.rules.duplicate-check=true}).
   * Default returns the class's simple name lower-cased; override for a stable public id
   * that survives class renames.
   */
  default String id() {
    String simple = getClass().getSimpleName();
    if (simple.endsWith("Rule")) {
      simple = simple.substring(0, simple.length() - 4);
    }
    // camelCase → kebab-case, so DuplicateInvoice → duplicate-invoice.
    StringBuilder out = new StringBuilder(simple.length() + 4);
    for (int i = 0; i < simple.length(); i++) {
      char c = simple.charAt(i);
      if (Character.isUpperCase(c)) {
        if (i > 0) out.append('-');
        out.append(Character.toLowerCase(c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
