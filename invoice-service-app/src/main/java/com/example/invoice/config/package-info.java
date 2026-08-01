/**
 * Composition root: where the ports meet their adapters.
 *
 * <p><b>Explicit beans, not autoconfiguration.</b> Spring Boot autoconfiguration exists so a
 * third-party jar can wire itself into an application that does not know about it. These are sibling
 * modules in one repository, so that problem does not arise — and {@code @ConditionalOnMissingBean}
 * chains fail silently when a condition does not match, which is precisely the debugging experience
 * you do not want during an incident. Explicit beans are greppable and fail loudly.
 *
 * <p><b>The two switches, restated because they are the most likely thing to be misused.</b>
 * {@code alerting.enabled=false} installs the pass-through guard and disables blocking — a safety
 * decision. {@code alerting.email.enabled=false} silences mail only, and every defect is still
 * detected, recorded and blocked. Under pressure, the second is safe and the first is not.
 *
 * <p><b>Shutdown order.</b> Caches close before the email publisher, so a final notification still
 * has a live dispatcher to flush through. Spring derives this from the bean dependency graph; do not
 * "simplify" it by removing the dependency.
 *
 * <p><b>Two beans you must supply.</b> {@code ReferentialGateway} (wrapping your existing
 * {@code ReferentialServiceApi}), {@code AlertEmailPort} (wrapping your mail endpoint), and
 * {@code RecordCodec} (wrapping your JSON library). Placeholder implementations are provided and
 * throw on use, so a missing wiring fails immediately and loudly rather than silently degrading.
 *
 * @readme.module Invoice Service — Invoice Service Wiring
 * @readme.order 0
 */
package com.example.invoice.config;
