package com.sg.domaininterface.rule.party;

/**
 * Whether details carrying a given defect may be served for invoice registration.
 *
 * <p>Two values, because the useful distinction is binary: the record either supports an invoice or
 * it does not. Anything finer would be a reporting concern rather than a decision anyone acts on.
 *
 * <p>Deliberately not named "severity". Severity is how loudly to complain; servability is whether
 * the data can be used. Conflating them — as an earlier revision did — puts a business decision
 * under the control of notification settings.
 */
public enum Servability {

    /** The record is usable. Any defect is recorded for correction but does not stop processing. */
    SERVABLE,

    /**
     * The record cannot support invoice registration. It is withheld until a correction is supplied,
     * because serving it would produce an invoice that cannot be registered.
     */
    BLOCKING
}
