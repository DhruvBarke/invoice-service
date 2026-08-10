package com.sg.domaininterface.model.provider;

/**
 * What a provider is set up to do, for one fee category at one SG entity.
 *
 * <p>The two flags gate money and books respectively: {@code paymentActivation} says invoices from
 * this provider may be paid, {@code accountingActivation} says they may be booked. They are held
 * per {@code (provider, fee category, entity)} because a provider can be live for one kind of work
 * and still being onboarded for another.
 *
 * <p><b>Both are primitives, and absence is modelled by the {@link java.util.Optional} the lookup
 * returns rather than by a null here.</b> A provider with no setup row and a provider set up as
 * inactive are the same instruction — do not pay — but they are not the same fact, and only the
 * caller can decide whether to distinguish them.
 */
public record ProviderSetup(boolean paymentActivation, boolean accountingActivation) {
}
