/**
 * Party registration domain: the business rules and the ports through which they are reached.
 *
 * <p>This module answers three questions and nothing else. Which registration details describe a
 * party. What makes a set of details usable for invoice registration. What the outside world must
 * provide for those rules to run.
 *
 * <p><b>Zero dependencies, deliberately.</b> Not an aesthetic choice — it is the property the whole
 * structure rests on. The invoice inbound, invoice outbound and report mappers declare their facade
 * beans against {@code PartyRegistrationLookup}. If this module depended on the cache, those mappers
 * would transitively acquire a referential client, a quarantine DataSource and an SMTP port, and a
 * mapper unit test could not construct a bean without a database. A build-time enforcer rule keeps
 * this module dependency-free; treat a proposal to add one as an architectural change, not a
 * convenience.
 *
 * <p><b>Dependency direction.</b> Everything points inward. The cache and alerting modules depend on
 * this one; this one depends on nothing. Neither adapter knows the other exists — they meet only at
 * {@code ResponseGuard}, a port defined here.
 *
 * <p><b>What is a rule and what is a mechanism.</b> The line drawn throughout: if the business would
 * recognise a statement and want it reviewed, it belongs here. "A record with no SIREN cannot anchor
 * an invoice" is a rule. "Cache it for thirty minutes" is a mechanism. Detection of anomalies lives
 * here for exactly this reason — it was previously buried in the alerting adapter, where a business
 * invariant was expressed in the vocabulary of mail severity and could not be tested without SMTP
 * classes on the classpath.
 *
 * <p><b>Servability is a domain concept.</b> {@code Servability} decides whether details may be
 * served at all. Alerting derives its notification behaviour from it, never the reverse. This is what
 * guarantees that silencing email can never cause unusable data to reach invoice registration: the
 * two live in different modules, and the arrow only points one way.
 *
 * @readme.module Invoice Service — Domain
 * @readme.order 0
 * @readme.depends Nothing. Enforced by maven-enforcer, and load-bearing.
 */
package com.example.invoice.service.domain;
