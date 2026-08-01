/**
 * Caching adapter: implements the driving port by holding referential responses in memory.
 *
 * <p>Pure infrastructure. Every business decision it needs — whether a response may be served, which
 * duplicate wins — is delegated to the domain. What remains here is lifetime, eviction, coalescing
 * and memory layout.
 *
 * <p><b>Three independent key spaces.</b> SIREN, SIRET and BDR_ID each get their own map, ceiling,
 * sweep and counters. They are never cross-populated: a SIREN search returns the company's single
 * record with no guarantee it is the office a SIRET result describes, and one office row says nothing
 * about how many duplicate siblings share its SIRET. Independence also means a referential problem
 * confined to one flow cannot evict or throttle the other.
 *
 * <p><b>The hot path allocates nothing.</b> One map lookup, and the stored immutable list is returned
 * directly. Records are held as {@code List.of(x)} — no backing array — because the single-record
 * case is essentially every lookup.
 *
 * <p><b>Memory: deduplicate, do not compress.</b> Company-level fields repeat across every office of
 * a company, and every SIRET duplicate shares a SIREN by definition, so interning collapses them at
 * zero read cost. Byte-level compression would shrink records further but forces a decompress and
 * re-parse on every hit, trading ~100ns reads for ~10us ones — the wrong direction for a lookup
 * cache. See {@code StringPool}.
 *
 * <p><b>Four behaviours that exist to protect the referential</b>, each documented where implemented:
 * single-flight coalescing so concurrent misses on one key produce one call; negative caching so
 * unknown ids cannot loop; TTL jitter so entries loaded together do not expire together; and
 * refresh-ahead so no request pays referential latency at an expiry boundary.
 *
 * <p><b>Runs with no alerting.</b> Given {@code ResponseGuard.passThrough()} it needs no database, no
 * mail server and no quarantine table. That is the property that makes alerting an adapter rather
 * than a dependency, and a build-time rule stops the dependency being added.
 *
 * @readme.module Invoice Service — Caching Adapter
 * @readme.order 0
 * @readme.depends invoice-service-domain — model, rules and ports
 * @readme.depends third-parties — the existing ReferentialServiceApi, used by one adapter class only
 */
package com.example.invoice.service.cache;
