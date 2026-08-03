package com.example.invoice.service.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.invoice.mapper.einvoice.EInvoiceFacadeMapper;
import com.example.invoice.mapper.einvoice.FeeTypeMatcher;
import com.example.invoice.mapper.einvoice.MultipartExtractionService;
import com.example.invoice.mapper.einvoice.MultipartExtractionService.ExtractedAttachment;
import com.example.invoice.mapper.einvoice.model.invoice.Invoice;
import com.example.invoice.service.domain.port.in.PartyRegistrationUnavailableException;
import com.example.invoice.service.domain.port.in.UnavailabilityReason;
import com.example.invoice.service.registration.error.ErrorCode;
import com.example.invoice.service.registration.error.LifecycleEventType;
import com.example.invoice.service.registration.error.MappingError;
import com.example.invoice.service.registration.error.RegistrationOutcome;
import com.example.invoice.service.registration.port.InvoicePayableStore;
import com.example.invoice.service.registration.port.LifecycleEventPublisher;
import com.example.invoice.service.registration.port.RegistrationAlertNotifier;
import com.example.invoice.service.registration.rule.AttachmentPresentRule;
import com.example.invoice.service.registration.rule.BrokerageTradeFileRule;
import com.example.invoice.service.registration.rule.DuplicateInvoiceRule;
import com.example.invoice.service.registration.rule.LineItemsPresentRule;
import com.example.invoice.service.registration.rule.ValidationRegistry;
import com.example.invoice.service.registration.testsupport.Fixtures;
import com.example.invoice.service.registration.testsupport.Stubs;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * End-to-end pipeline behaviour, driven from the JSON fixtures in
 * {@code src/test/resources/einvoice-samples}.
 */
class InvoiceRegistrationServiceTest {

  // ── Harness ───────────────────────────────────────────────────────────────

  private record Harness(
      InvoiceRegistrationService service,
      Stubs.RecordingStore store,
      Stubs.RecordingPublisher publisher,
      Stubs.RecordingNotifier notifier) {}

  /** Full MARK rule set, matching the shipped application.yml. */
  private static ValidationRegistry markRules(boolean duplicateExists) {
    return ValidationRegistry.builder()
        .add(Business.MARK, new DuplicateInvoiceRule(ref -> duplicateExists))
        .add(Business.MARK, new AttachmentPresentRule())
        .add(Business.MARK, new BrokerageTradeFileRule())
        .add(Business.MARK, new LineItemsPresentRule())
        .build();
  }

  private static Harness harness(ValidationRegistry rules) {
    return harness(rules, new EInvoiceFacadeMapper(Stubs.lookup()), Stubs.matcher(),
        new Stubs.RecordingPublisher(), new Stubs.RecordingNotifier());
  }

  private static Harness harness(ValidationRegistry rules, EInvoiceFacadeMapper mapper,
                                 FeeTypeMatcher matcher,
                                 LifecycleEventPublisher publisher,
                                 RegistrationAlertNotifier notifier) {
    Stubs.RecordingStore store = new Stubs.RecordingStore();
    InvoiceRegistrationService svc = new InvoiceRegistrationService(
        mapper, matcher, new MultipartExtractionService(), rules, store, publisher, notifier);
    return new Harness(svc, store,
        publisher instanceof Stubs.RecordingPublisher rp ? rp : new Stubs.RecordingPublisher(),
        notifier instanceof Stubs.RecordingNotifier rn ? rn : new Stubs.RecordingNotifier());
  }

  private static boolean hasCode(RegistrationOutcome outcome, ErrorCode code) {
    return outcome.errors().stream().anyMatch(e -> e.code() == code);
  }

  private static MappingError find(RegistrationOutcome outcome, ErrorCode code) {
    return outcome.errors().stream().filter(e -> e.code() == code).findFirst().orElseThrow();
  }

  // ── Happy path ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("clean registration")
  class HappyPath {

    @Test
    @DisplayName("custody invoice with lines and an attachment registers with no errors")
    void registersCleanly() {
      Harness h = harness(markRules(false));
      Invoice inv = Fixtures.loadInvoice("custody-with-lines.json");

      RegistrationOutcome outcome = h.service().register(inv, List.of());

      assertEquals(RegistrationOutcome.Status.REGISTERED, outcome.status());
      assertNull(outcome.lifecycleEvent());
      assertNull(outcome.comment());
      assertFalse(outcome.hasErrors());
      assertTrue(outcome.isRegistered());
      assertEquals(0, h.publisher().events.size(), "a clean run queues no lifecycle event");
      assertEquals(0, h.notifier().alerts.size(), "a clean run raises no alert");
    }

    @Test
    @DisplayName("the JSON-embedded PDF satisfies the attachment rule without a multipart file")
    void embeddedAttachmentCounts() {
      Harness h = harness(markRules(false));
      RegistrationOutcome outcome = h.service()
          .register(Fixtures.loadInvoice("custody-with-lines.json"), List.of());
      assertFalse(hasCode(outcome, ErrorCode.MISSING_ATTACHMENT));
    }

    @Test
    @DisplayName("resolved fee identity and business reach the persisted row")
    void feeIdentityReachesPersistence() {
      Harness h = harness(markRules(false));
      h.service().register(Fixtures.loadInvoice("custody-with-lines.json"), List.of());

      InvoicePayableStore.PersistRequest req = h.store().last.get();
      assertNotNull(req);
      assertEquals("F01", req.feeId(), "the referential id must land on the row");
      assertEquals("CUSTODY", req.feeType());
      assertEquals(Business.MARK, req.business());
      assertEquals("EINVOICE", req.source(), "this pipeline is e-invoice only");
      assertNotNull(req.model());
      assertEquals("CUS0226368", req.model().getInvoicePayable().getProviderReference(),
          "the e-invoice id is the supplier's reference");
      assertNull(req.model().getInvoiceReference(),
          "invoiceReference is minted by the store from seq_invoice_reference, so it is still "
              + "null on the request the store receives");
    }

    @Test
    @DisplayName("fee category is copied onto the payable model, not just the row")
    void feeCategorySeededOntoModel() {
      Harness h = harness(markRules(false));
      h.service().register(Fixtures.loadInvoice("custody-with-lines.json"), List.of());

      InvoicePayableStore.PersistRequest req = h.store().last.get();
      assertEquals("CUSTODY", req.model().getFeeCategory());
      assertEquals("F01", req.model().getInvoicePayable().getFeeCategoryCode());
    }
  }

  // ── Spec rules ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("spec rule 1 — duplicate invoice")
  class DuplicateRule {

    @Test
    @DisplayName("a duplicate provider reference is CANCELLED + REFUSED(DOUBLON)")
    void duplicateIsRefused() {
      Harness h = harness(markRules(true));
      RegistrationOutcome outcome = h.service()
          .register(Fixtures.loadInvoice("custody-with-lines.json"), List.of());

      assertEquals(RegistrationOutcome.Status.CANCELLED, outcome.status());
      assertEquals(LifecycleEventType.REFUSED, outcome.lifecycleEvent());
      assertEquals("DOUBLON", outcome.lifecycleReasonCode());
      assertTrue(outcome.comment().contains("invoice already exists"),
          "the spec dictates this exact comment on the row");
    }

    @Test
    @DisplayName("a refusal queues exactly one lifecycle event carrying the row id")
    void refusalQueuesLifecycleEvent() {
      Harness h = harness(markRules(true));
      h.service().register(Fixtures.loadInvoice("custody-with-lines.json"), List.of());

      assertEquals(1, h.publisher().events.size());
      LifecycleEventPublisher.PendingLifecycleEvent e = h.publisher().events.get(0);
      assertEquals(Stubs.RecordingStore.ROW_ID, e.invoicePayableId());
      assertEquals(LifecycleEventType.REFUSED, e.type());
      assertEquals("DOUBLON", e.reasonCode());
      assertEquals(210, e.type().cdarCode());
      assertEquals("CUS0226368", e.invoiceReference());
    }

    @Test
    @DisplayName("exactly one alert per failed invoice, carrying every error")
    void oneAlertPerFailedInvoice() {
      Harness h = harness(markRules(true));
      RegistrationOutcome outcome = h.service()
          .register(Fixtures.loadInvoice("custody-with-lines.json"), List.of());

      assertEquals(1, h.notifier().alerts.size());
      RegistrationAlertNotifier.RegistrationAlert alert = h.notifier().alerts.get(0);
      assertEquals(outcome.errors().size(), alert.errors().size());
      assertEquals(Business.MARK, alert.business());
      assertEquals(Stubs.RecordingStore.ROW_ID, alert.invoicePayableId());
    }
  }

  @Nested
  @DisplayName("spec rule 2 — attachment presence")
  class AttachmentRule {

    @Test
    @DisplayName("no attachment in either channel is CANCELLED + SUSPENDED(JUSTIF_ABS)")
    void missingAttachmentIsSuspended() {
      Harness h = harness(markRules(false));
      RegistrationOutcome outcome = h.service()
          .register(Fixtures.loadInvoice("no-attachment.json"), List.of());

      assertTrue(hasCode(outcome, ErrorCode.MISSING_ATTACHMENT));
      assertEquals(RegistrationOutcome.Status.CANCELLED, outcome.status());
      assertEquals(LifecycleEventType.SUSPENDED, outcome.lifecycleEvent());
      assertEquals("JUSTIF_ABS", outcome.lifecycleReasonCode());
      assertEquals(208, outcome.lifecycleEvent().cdarCode());
    }

    @Test
    @DisplayName("a multipart file alone satisfies the rule when the JSON body has none")
    void multipartAloneSatisfiesTheRule() {
      Harness h = harness(markRules(false));
      RegistrationOutcome outcome = h.service()
          .register(Fixtures.loadInvoice("no-attachment.json"),
              List.of(Fixtures.pdf("supplied-separately.pdf")));

      assertFalse(hasCode(outcome, ErrorCode.MISSING_ATTACHMENT),
          "the rule asks whether an attachment exists at all, not which channel carried it");
    }

    @Test
    @DisplayName("a corrupt attachment is dropped upstream and reads as absent")
    void corruptAttachmentReadsAsAbsent() {
      Harness h = harness(markRules(false));
      RegistrationOutcome outcome = h.service()
          .register(Fixtures.loadInvoice("corrupt-attachment.json"), List.of());

      assertTrue(hasCode(outcome, ErrorCode.MISSING_ATTACHMENT),
          "MultipartExtractionService drops the file on the magic-byte check, so the "
              + "pipeline sees zero attachments — which is why no rule needs its own "
              + "corruption check");
    }
  }

  @Nested
  @DisplayName("spec rule 3 — brokerage trade file")
  class TradeFileRule {

    @Test
    @DisplayName("BROKERAGE_PRINCIPAL without a trade file is SUSPENDED")
    void brokerageWithoutTradeFileIsSuspended() {
      Harness h = harness(markRules(false));
      RegistrationOutcome outcome = h.service()
          .register(Fixtures.loadInvoice("brokerage-principal.json"), List.of());

      assertTrue(hasCode(outcome, ErrorCode.MISSING_TRADE_FILE));
      assertEquals(LifecycleEventType.SUSPENDED, outcome.lifecycleEvent());
    }

    @Test
    @DisplayName("a .csv trade file satisfies the rule")
    void csvSatisfies() {
      Harness h = harness(markRules(false));
      RegistrationOutcome outcome = h.service()
          .register(Fixtures.loadInvoice("brokerage-principal.json"),
              List.of(Fixtures.tradeCsv("trades.csv")));
      assertFalse(hasCode(outcome, ErrorCode.MISSING_TRADE_FILE));
    }

    @Test
    @DisplayName("an .xlsx trade file satisfies the rule")
    void xlsxSatisfies() {
      Harness h = harness(markRules(false));
      RegistrationOutcome outcome = h.service()
          .register(Fixtures.loadInvoice("brokerage-principal.json"),
              List.of(Fixtures.tradeXlsx("trades.xlsx")));
      assertFalse(hasCode(outcome, ErrorCode.MISSING_TRADE_FILE));
    }

    @Test
    @DisplayName("the underscore inside BROKERAGE_PRINCIPAL survives marker parsing")
    void feeTypeTailWithUnderscoreResolves() {
      Harness h = harness(markRules(false));
      h.service().register(Fixtures.loadInvoice("brokerage-principal.json"),
          List.of(Fixtures.tradeCsv("trades.csv")));

      assertEquals("F04", h.store().last.get().feeId(),
          "only the first two underscores are separators — the tail must stay intact");
    }
  }

  @Nested
  @DisplayName("spec rule 4 — line items")
  class LineItemRule {

    @Test
    @DisplayName("CUSTODY with no lines → INCOMPLETE, alert but NO lifecycle event")
    void noLinesIsIncompleteAndAlertOnly() {
      Harness h = harness(markRules(false));
      RegistrationOutcome outcome = h.service()
          .register(Fixtures.loadInvoice("custody-no-lines.json"), List.of());

      assertTrue(hasCode(outcome, ErrorCode.EMPTY_LINE_ITEMS));
      assertEquals(RegistrationOutcome.Status.INCOMPLETE, outcome.status(),
          "users add the missing lines later — CANCELLED would block that path");
      assertNull(outcome.lifecycleEvent());
      assertEquals(0, h.publisher().events.size(), "INCOMPLETE queues no lifecycle event");
      assertEquals(1, h.notifier().alerts.size(), "but ops is still told the row is sitting there");
    }
  }

  // ── Marker + fee-type failures ────────────────────────────────────────────

  @Nested
  @DisplayName("marker and fee-type resolution")
  class MarkerFailures {

    @Test
    @DisplayName("unknown business is captured and no rules run for it")
    void unknownBusinessRunsNoRules() {
      Harness h = harness(markRules(false));
      RegistrationOutcome outcome = h.service()
          .register(Fixtures.loadInvoice("unknown-business.json"), List.of());

      assertTrue(hasCode(outcome, ErrorCode.BUSINESS_UNKNOWN));
      assertEquals(LifecycleEventType.REFUSED, outcome.lifecycleEvent());
      assertFalse(hasCode(outcome, ErrorCode.MISSING_ATTACHMENT),
          "rules are scoped per business; an unresolved business runs none of them, even "
              + "though this fixture would otherwise trip the attachment rule");
    }

    @Test
    @DisplayName("an ambiguous fee type refuses rather than guessing")
    void ambiguousFeeTypeIsRefused() {
      Harness h = harness(markRules(false));
      RegistrationOutcome outcome = h.service()
          .register(Fixtures.loadInvoice("ambiguous-feetype.json"), List.of());

      assertTrue(hasCode(outcome, ErrorCode.FEETYPE_UNRESOLVED));
      assertEquals(LifecycleEventType.REFUSED, outcome.lifecycleEvent());
      assertTrue(find(outcome, ErrorCode.FEETYPE_UNRESOLVED).detail().contains("Ambiguous"),
          "the failure detail should say WHY, so ops can fix the referential or the marker");
    }

    @Test
    @DisplayName("the raw marker fee type is persisted when the matcher could not resolve it")
    void rawFeeTypePersistedOnFailure() {
      Harness h = harness(markRules(false));
      h.service().register(Fixtures.loadInvoice("ambiguous-feetype.json"), List.of());

      assertNull(h.store().last.get().feeId(), "no referential id was resolved");
      assertEquals("BROKERAGE", h.store().last.get().feeType(),
          "the raw token is kept so ops can see what arrived");
    }

    @Test
    @DisplayName("a blank endpoint value yields MARKER_MALFORMED, not a crash")
    void blankEndpointIsMalformed() {
      Harness h = harness(markRules(false));
      Invoice inv = Fixtures.loadInvoice("custody-with-lines.json");
      inv.getAccountingCustomerParty().getParty().getEndpointId().setValue("   ");

      RegistrationOutcome outcome = h.service().register(inv, List.of());
      assertTrue(hasCode(outcome, ErrorCode.MARKER_MALFORMED));
    }

    @Test
    @DisplayName("an absent endpoint element yields MARKER_MALFORMED")
    void absentEndpointIsMalformed() {
      Harness h = harness(markRules(false));
      Invoice inv = Fixtures.loadInvoice("custody-with-lines.json");
      inv.getAccountingCustomerParty().getParty().setEndpointId(null);

      RegistrationOutcome outcome = h.service().register(inv, List.of());
      assertTrue(hasCode(outcome, ErrorCode.MARKER_MALFORMED));
    }

    @Test
    @DisplayName("an absent customer party yields MARKER_MALFORMED")
    void absentCustomerPartyIsMalformed() {
      Harness h = harness(markRules(false));
      Invoice inv = Fixtures.loadInvoice("custody-with-lines.json");
      inv.setAccountingCustomerParty(null);

      RegistrationOutcome outcome = h.service().register(inv, List.of());
      assertTrue(hasCode(outcome, ErrorCode.MARKER_MALFORMED));
    }

    @Test
    @DisplayName("a marker with no fee-type tail yields MARKER_MALFORMED")
    void missingFeeTypeTailIsMalformed() {
      Harness h = harness(markRules(false));
      Invoice inv = Fixtures.loadInvoice("custody-with-lines.json");
      inv.getAccountingCustomerParty().getParty().getEndpointId().setValue("552120222_MARK");

      RegistrationOutcome outcome = h.service().register(inv, List.of());
      assertTrue(hasCode(outcome, ErrorCode.MARKER_MALFORMED));
    }

    @Test
    @DisplayName("a fee-type referential that blows up is captured, not propagated")
    void feeTypeProviderFailureIsCaptured() {
      FeeTypeMatcher exploding = new FeeTypeMatcher(() -> {
        throw new IllegalStateException("referential DB unreachable");
      });
      Harness h = harness(markRules(false), new EInvoiceFacadeMapper(Stubs.lookup()),
          exploding, new Stubs.RecordingPublisher(), new Stubs.RecordingNotifier());

      RegistrationOutcome outcome = h.service()
          .register(Fixtures.loadInvoice("custody-with-lines.json"), List.of());

      assertTrue(hasCode(outcome, ErrorCode.FEETYPE_UNRESOLVED));
      assertTrue(find(outcome, ErrorCode.FEETYPE_UNRESOLVED).detail()
          .contains("referential DB unreachable"));
      assertNotNull(find(outcome, ErrorCode.FEETYPE_UNRESOLVED).cause(),
          "the original exception must ride along for the alert's stack trace");
    }
  }

  // ── Failure isolation ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("failures in one step never break the pipeline")
  class FailureIsolation {

    @Test
    @DisplayName("a party-lookup failure becomes PARTY_LOOKUP_FAILED and still persists a row")
    void partyLookupFailureIsCaptured() {
      EInvoiceFacadeMapper mapper = new EInvoiceFacadeMapper(
          Stubs.throwingLookup(new PartyRegistrationUnavailableException(
              UnavailabilityReason.UPSTREAM_UNAVAILABLE, "SIREN", "552120222",
              "referential timed out")));
      Harness h = harness(markRules(false), mapper, Stubs.matcher(),
          new Stubs.RecordingPublisher(), new Stubs.RecordingNotifier());

      RegistrationOutcome outcome = h.service()
          .register(Fixtures.loadInvoice("custody-with-lines.json"), List.of());

      assertTrue(hasCode(outcome, ErrorCode.PARTY_LOOKUP_FAILED));
      assertEquals(LifecycleEventType.SUSPENDED, outcome.lifecycleEvent());
      assertEquals("SIRET_ERR", outcome.lifecycleReasonCode());
      assertEquals(1, h.store().calls, "the row is written even when mapping failed");
      assertNull(h.store().last.get().model(), "…with a null payload, which is the honest record");
    }

    @Test
    @DisplayName("an unexpected mapper exception becomes MAPPING_ERROR")
    void unexpectedMapperExceptionIsCaptured() {
      EInvoiceFacadeMapper mapper = new EInvoiceFacadeMapper(
          Stubs.throwingLookup(new IllegalStateException("boom")));
      Harness h = harness(markRules(false), mapper, Stubs.matcher(),
          new Stubs.RecordingPublisher(), new Stubs.RecordingNotifier());

      RegistrationOutcome outcome = h.service()
          .register(Fixtures.loadInvoice("custody-with-lines.json"), List.of());

      assertTrue(hasCode(outcome, ErrorCode.MAPPING_ERROR));
      assertEquals(LifecycleEventType.REFUSED, outcome.lifecycleEvent());
    }

    @Test
    @DisplayName("a rule that throws is contained and reported, not propagated")
    void throwingRuleIsContained() {
      ValidationRegistry rules = ValidationRegistry.builder()
          .add(Business.MARK, ctx -> { throw new IllegalStateException("rule bug"); })
          .build();
      Harness h = harness(rules);

      RegistrationOutcome outcome = h.service()
          .register(Fixtures.loadInvoice("custody-with-lines.json"), List.of());

      assertTrue(hasCode(outcome, ErrorCode.MAPPING_ERROR));
      assertTrue(find(outcome, ErrorCode.MAPPING_ERROR).detail().contains("threw unexpectedly"));
    }

    @Test
    @DisplayName("a rule returning null is treated as a pass")
    void nullReturningRuleIsAPass() {
      ValidationRegistry rules = ValidationRegistry.builder()
          .add(Business.MARK, ctx -> null)
          .build();
      Harness h = harness(rules);

      RegistrationOutcome outcome = h.service()
          .register(Fixtures.loadInvoice("custody-with-lines.json"), List.of());
      assertTrue(outcome.isRegistered());
    }

    @Test
    @DisplayName("a lifecycle publisher failure is recorded but does not unwind the row")
    void publisherFailureIsRecorded() {
      Harness h = harness(markRules(true), new EInvoiceFacadeMapper(Stubs.lookup()),
          Stubs.matcher(), new Stubs.ThrowingPublisher(), new Stubs.RecordingNotifier());

      RegistrationOutcome outcome = h.service()
          .register(Fixtures.loadInvoice("custody-with-lines.json"), List.of());

      assertEquals(RegistrationOutcome.Status.CANCELLED, outcome.status(),
          "the outcome was already decided before the publisher was called");
      assertEquals(1, h.store().calls, "the row is durable regardless");
    }

    @Test
    @DisplayName("an alert notifier failure never fails the registration")
    void notifierFailureIsSwallowed() {
      Harness h = harness(markRules(true), new EInvoiceFacadeMapper(Stubs.lookup()),
          Stubs.matcher(), new Stubs.RecordingPublisher(), new Stubs.ThrowingNotifier());

      RegistrationOutcome outcome = h.service()
          .register(Fixtures.loadInvoice("custody-with-lines.json"), List.of());

      assertEquals(RegistrationOutcome.Status.CANCELLED, outcome.status(),
          "SMTP being down must not turn a stored CANCELLED row into a caller-facing failure");
    }

    @Test
    @DisplayName("a row is persisted even for a totally malformed invoice")
    void rowIsAlwaysPersisted() {
      Harness h = harness(markRules(false));
      Invoice inv = Fixtures.loadInvoice("custody-with-lines.json");
      inv.getAccountingCustomerParty().getParty().getEndpointId().setValue("no-underscores-here");

      h.service().register(inv, List.of());
      assertNotNull(h.store().last.get(),
          "a failed registration is a data point, not a discard");
    }
  }

  // ── Contract guards ───────────────────────────────────────────────────────

  @Nested
  @DisplayName("constructor and argument contracts")
  class Contracts {

    @Test
    @DisplayName("a null e-invoice is rejected outright")
    void nullInvoiceRejected() {
      Harness h = harness(markRules(false));
      assertThrows(NullPointerException.class, () -> h.service().register(null, List.of()));
    }

    @Test
    @DisplayName("null multipart list is treated as empty, not a crash")
    void nullAttachmentsTreatedAsEmpty() {
      Harness h = harness(markRules(false));
      RegistrationOutcome outcome =
          h.service().register(Fixtures.loadInvoice("custody-with-lines.json"), null);
      assertTrue(outcome.isRegistered());
    }

    @Test
    @DisplayName("every collaborator is mandatory")
    void collaboratorsAreMandatory() {
      EInvoiceFacadeMapper mapper = new EInvoiceFacadeMapper(Stubs.lookup());
      FeeTypeMatcher matcher = Stubs.matcher();
      MultipartExtractionService extractor = new MultipartExtractionService();
      ValidationRegistry rules = markRules(false);
      Stubs.RecordingStore store = new Stubs.RecordingStore();
      Stubs.RecordingPublisher pub = new Stubs.RecordingPublisher();
      Stubs.RecordingNotifier notif = new Stubs.RecordingNotifier();

      assertThrows(NullPointerException.class, () -> new InvoiceRegistrationService(
          null, matcher, extractor, rules, store, pub, notif));
      assertThrows(NullPointerException.class, () -> new InvoiceRegistrationService(
          mapper, null, extractor, rules, store, pub, notif));
      assertThrows(NullPointerException.class, () -> new InvoiceRegistrationService(
          mapper, matcher, null, rules, store, pub, notif));
      assertThrows(NullPointerException.class, () -> new InvoiceRegistrationService(
          mapper, matcher, extractor, null, store, pub, notif));
      assertThrows(NullPointerException.class, () -> new InvoiceRegistrationService(
          mapper, matcher, extractor, rules, null, pub, notif));
      assertThrows(NullPointerException.class, () -> new InvoiceRegistrationService(
          mapper, matcher, extractor, rules, store, null, notif));
      assertThrows(NullPointerException.class, () -> new InvoiceRegistrationService(
          mapper, matcher, extractor, rules, store, pub, null));
    }
  }
}
