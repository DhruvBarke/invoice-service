package com.sg.domaininterface.model.provider;

/**
 * One standing settlement instruction: an account SG holds on file for a provider.
 *
 * <p>Held per {@code (provider, currency, SG entity, fee category)}, and there is normally more
 * than one — a provider settles euros and dollars through different accounts. The invoice names
 * the account it wants paying into; matching it against this set is what stops a payment being
 * sent to an account nobody agreed to.
 *
 * <p>Every field is nullable. A referential row missing its swift code is a real row that a real
 * comparison has to cope with, and rejecting it here would make the offending entry impossible to
 * log or report.
 */
public record SsiDetails(String accountNumber, String bankName, String swiftCode) {
}
