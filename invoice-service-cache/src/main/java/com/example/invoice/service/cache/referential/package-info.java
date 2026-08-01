/**
 * The referential adapter: the only place the existing {@code ReferentialServiceApi} is visible.
 *
 * <p>Everything else in this repository works in domain types. If the referential's request shape or
 * response DTO changes, this package is the entire blast radius.
 *
 * <p>It also owns the enum translation — the domain's {@code RegistrationType} to the referential's
 * own — which exists so that {@code invoice-mapper} never acquires a dependency on third-parties.
 *
 * <p><b>Adopting this into your repo:</b> the accessor names used here are an assumption about your
 * DTO ({@code getGoldenBdrId()}, a {@code List} return from {@code getRegistrationDetails}). Adjust
 * them to match; nothing else references the referential's types.
 *
 * @readme.section Referential adapter
 * @readme.order 60
 */
package com.example.invoice.service.cache.referential;
