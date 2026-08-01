# Invoice Service — party-registration cache, alerting, and invoice mappers

Hexagonal multi-module Maven build. Ships two capabilities:

1. A cached, quality-guarded **party-registration lookup** consumed by the invoice pipeline
   (SIREN / SIRET / BDR-id keyed, with anomaly detection, quarantine, and email alerting).
2. **Bidirectional invoice mappers** — `InvoicePayable ↔ UBL Invoice` and
   `InvoicePayable → Flux 10.1 ReportModel` — no MapStruct, plain constructor-injected classes.

> Per-module READMEs are generated from `package-info.java` — run `mvn -P readme package`.
> This file is the only hand-written one.

---

## Modules

| Module | Depends on | Role |
|---|---|---|
| `invoice-service-domain` | **nothing** (enforced) | Model, business rules, ports |
| `invoice-service-cache` | domain, third-parties | Driving-port adapter: in-memory caching |
| `invoice-service-alerting` | domain | Driven-port adapter: detection, quarantine, email |
| `invoice-mapper` | **domain only** (enforced) | Invoice facade mappers (einvoice + Flux 10 report) |
| `invoice-service-app` | all of the above | Composition root |
| `build-tools/readme-doclet` | — | Build-time README generation |

Dependencies point inward. The two adapters never see each other; they meet at `ResponseGuard`,
a port defined in the domain.

### The enforcer rules are load-bearing

Two `maven-enforcer` rules exist and are not decoration:

- `invoice-service-domain` must have **no** compile dependencies.
- `invoice-mapper` must depend on **domain only** — no cache, no alerting, no third-parties,
  no Spring, no SQL, no Jackson.

Sibling modules in one repository drift together within a year if nothing objects, and the
coupling stays invisible until someone needs a `DataSource` to run a mapper unit test. **Run
`mvn verify` in CI, not just `mvn test`** — `test` does not execute enforcer.

---

## Adoption checklist

**1. Wire the referential gateway.** Rename
`invoice-service-cache/.../referential/ReferentialServiceApiGateway.java.template` to `.java`,
uncomment the `third-parties` dependency in that module's pom, and adjust the accessor names to
match your DTO. **This is the only file that sees your referential's types** — if the request
shape or response DTO changes later, this file is the entire blast radius.

**2. Supply three beans.** `AdapterPlaceholders` documents each; they throw on use so a missing
wiring fails loudly rather than degrading silently.

| Bean | Wraps |
|---|---|
| `ReferentialGateway` | your `ReferentialServiceApi` (template provided) |
| `AlertEmailPort` | your mail endpoint — typically two lines |
| `RecordCodec` | your JSON library — so this project imposes none |

**3. Run the migration.**
`invoice-service-app/src/main/resources/db/migration/V1__party_registration_quarantine.sql`,
renumbered to follow your latest version. The table name (`party_registration_quarantine`) is
intentionally descriptive — it stores party-registration data-quality defects, and renaming
the table would obscure the meaning.

**4. Merge the config.** `application-invoice-service.yml` into your `application.yml`, or add
`invoice-service` to `spring.profiles.include`.

**5. Optional.** `AlertingAdminController.java.template` → `.java` if you want runtime email
muting and have `spring-boot-starter-web`. **Secure it behind your existing admin auth.**

---

## The two switches — read before an incident

| Property | Effect |
|---|---|
| `invoice.service.alerting.email.enabled=false` | **Safe.** Silences mail. Every defect is still detected, recorded in the quarantine table, correctable, and blocking defects still block. |
| `invoice.service.alerting.enabled=false` | **Safety switch.** Installs the pass-through guard: no detection, no quarantine rows, **no blocking**. |

Under pressure someone will reach for whichever they find first. Put this table in the runbook.

A consequence worth stating: muting email **does not** stop invoices being rejected. If blocking
defects are rejecting invoices and you mute the mail, they will keep being rejected — that is
intended. The only break-glass for blocking is the second row, and it should be a reviewed change.

---

## How the quarantine workflow runs

1. A referential response fails a domain rule (`AnomalyDetector`).
2. **Blocking** defect (no SIREN, nothing found) → withheld,
   `PartyRegistrationUnavailableException` carrying a quarantine row id. **Servable** defect
   (no SIRET, duplicates, golden mismatch) → served normally *and* recorded.
3. A row is written to `party_registration_quarantine`. One email goes out — **once per
   defect**, gated on `notified_at` in the database so the guarantee survives restarts and
   holds across pods.
4. An operator supplies `corrected_payload`. It outranks the referential immediately.
5. `QuarantinePoller` propagates the change to every instance within `poll-interval`.
6. Once upstream KYC is fixed, soft-delete the row — or let `auto-retire-resolved` do it when
   the response comes back clean.

The **table is the system of record**; email is only notification. That asymmetry is what makes
an email-only channel safe.

---

## Invoice mappers (`invoice-mapper` module)

Two facade services, both constructor-injected with `PartyRegistrationLookup`:

- **`EInvoiceMappingService`** — `InvoicePayable ⇄ UBL Invoice` (bidirectional).
  Callers pass attachment bytes directly (`AttachmentPayload`); the mapper does not fetch from
  any document store. Outbound builds a UBL Invoice with embedded base64 attachments; inbound
  produces `InvoicePayableModel` + `List<InvoiceItem>` and companion
  `MultipartExtractionService` extracts the attachments as raw byte payloads (Spring
  `MultipartFile` is not on this module's classpath by design).
- **`ReportMappingService`** — `InvoicePayable → Flux 10.1 ReportModel` (one-way).
  Composes a Flux 10 transmission with `ReportDocument` header, `TransactionsReport` and
  optional line items.

Both facades are plain Java. **No `@Mapper` anywhere.** No MapStruct processor, no Spring bean
formation. Bringing up either facade is a two-line construction; the smoke tests in
`invoice-mapper/src/test/java/.../*SmokeTest.java` show how.

---

## Before you rely on this

- **Test the referential integration.** The gateway template guesses accessor names on your
  DTO — adjust them, run `mvn -pl invoice-service-cache -am test`.
- **Tune `digest-interval` deliberately.** It converts an alert storm into one message. Longer
  is usually better — aggregation delays, never loses. Seconds reintroduces the storm it
  prevents.
- **Check `pooledStringHits` vs `entries` in `CacheStats`.** If interning is not earning its
  keep for your access pattern, set `string-pool-max-entries: 0` and reclaim the pool's own
  footprint.
