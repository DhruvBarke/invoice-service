/**
 * The vocabulary: what a party's registration details are, and how they are identified.
 *
 * <p>These types are deliberately decoupled from the referential's own DTOs. The gateway adapter
 * translates at the boundary, so a field rename upstream stops at one file instead of rippling
 * through the mappers.
 *
 * <p><b>Two identifier levels.</b> A party carries identity at both granularities at once: the
 * elementary (office) triple, the third-party (company) triple, and a golden id collapsing duplicate
 * elementary rows onto one master. Invoice registration always uses the golden details.
 *
 * <p><b>Normalization lives on the identifier types themselves.</b> {@code RegistrationType} and
 * {@code KeySpace} know how to canonicalize a value. Without a single shared normalizer the cache,
 * the quarantine table and the mappers would disagree about whether {@code "123 456 789"} and
 * {@code "123456789"} are the same party — a disagreement that surfaces as duplicate cache entries
 * and orphaned quarantine rows, both painful to diagnose after the fact.
 *
 * @readme.section Model
 * @readme.order 10
 */
package com.example.invoice.service.domain.model;
