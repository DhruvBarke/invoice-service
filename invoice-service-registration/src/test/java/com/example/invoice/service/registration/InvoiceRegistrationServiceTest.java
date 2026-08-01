package com.example.invoice.service.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.invoice.mapper.einvoice.EInvoiceFacadeMapper;
import com.example.invoice.mapper.einvoice.FeeTypeMatcher;
import com.example.invoice.mapper.einvoice.MultipartExtractionService;
import com.example.invoice.mapper.einvoice.MultipartExtractionService.ExtractedAttachment;
import com.example.invoice.mapper.einvoice.model.invoice.AccountingCustomerParty;
import com.example.invoice.mapper.einvoice.model.invoice.AccountingSupplierParty;
import com.example.invoice.mapper.einvoice.model.invoice.CodedValue;
import com.example.invoice.mapper.einvoice.model.invoice.Invoice;
import com.example.invoice.mapper.einvoice.model.invoice.Party;
import com.example.invoice.mapper.einvoice.model.invoice.PartyLegalEntity;
import com.example.invoice.mapper.einvoice.model.invoice.SchemeID;
import com.example.invoice.service.domain.model.PartyRegistrationDetails;
import com.example.invoice.service.domain.port.in.PartyRegistrationLookup;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end pipeline behaviour, driven entirely from in-memory stubs.
 *
 * <p>This class existing without a Spring context, a DataSource or an SMTP endpoint is the
 * point of the module's enforcer rule — if a future change makes the orchestrator need any of
 * those, this file stops compiling.
 */
class InvoiceRegistrationServiceTest {

  private static final PartyRegistrationDetails ACME = new PartyRegistrationDetails(
      "ELEM-9", "Lyon branch", "LYON", "TP-1", "Acme SA", "ACME",
      "BDR-G-001", "Acme SA", "ACME", "123456789", "12345678900012", List.of());

  // ── Stubs ─────────────────────────────────────────────────────────────────

  private static PartyRegistrationLookup lookupStub() {
    return new PartyRegistrationLookup() {
      public Optional<PartyRegistrationDetails> findByBdrId(String b) { return Optional.of(ACME); }
      public Optional<PartyRegistrationDetails> findBySiren(String s) { return Optional.of(ACME); }
      public Optional<PartyRegistrationDetails> findBySiret(String s) { return Optional.of(ACME); }
      public List<PartyRegistrationDetails> findAllBySiret(String s) { return List.of(ACME); }
    };
  }

  /** Referential covering the fee types the rules key on. */
  private static FeeTypeMatcher matcherStub() {
    Map<String, String> referential = Map.of(
        "F01", "CUSTODY",
        "F02", "EXCHANGE",
        "F03", "CLEARING",
        "F04", "BROKERAGE_PRINCIPAL",
        "F05", "BROKERAGE_AGENCY");
    return new FeeTypeMatcher(() -> referential);
  }

  /** Records what was persisted so assertions can inspect it. */
  private static final class RecordingStore implements InvoicePayableStore {
    final AtomicReference<PersistRequest> last = new AtomicReference<>();
    @Override public long persist(PersistRequest request) {
      last.set(request);
      return 42L;
    }
  }

  private static final class RecordingPublisher implements LifecycleEventPublisher {
    final List<PendingLifecycleEvent> events = new ArrayList<>();
    @Override public void publish(PendingLifecycleEvent e) { events.add(e); }
  }

  private static final class RecordingNotifier implements RegistrationAlertNotifier {
    final List<RegistrationAlert> alerts = new ArrayList<>();
    @Override public void notify(RegistrationAlert a) { alerts.add(a); }
  }

  /** Full MARK rule set, matching the shipped application.yml. */
  private static ValidationRegistry markRules(boolean duplicateExists) {
    return ValidationRegistry.builder()
        .add(Business.MARK, new DuplicateInvoiceRule(ref -> duplicateExists))
        .add(Business.MARK, new AttachmentPresentRule())
        .add(Business.MARK, new BrokerageTradeFileRule())
        .add(Business.MARK, new LineItemsPresentRule())
        .build();
  }

  private record Harness(
      InvoiceRegistrationService service,
      RecordingStore store,
      RecordingPublisher publisher,
      RecordingNotifier notifier) {}

  private static Harness harness(ValidationRegistry rules) {
    RecordingStore store = new RecordingStore();
    RecordingPublisher publisher = new RecordingPublisher();
    RecordingNotifier notifier = new RecordingNotifier();
    InvoiceRegistrationService svc = new InvoiceRegistrationService(
        new EInvoiceFacadeMapper(lookupStub()),
        matcherStub(),
        new MultipartExtractionService(),
        rules, store, publisher, notifier);
    return new Harness(svc, store, publisher, notifier);
  }

  /** Minimal but structurally valid e-invoice with the given receiver endpoint marker. */
  private static Invoice invoiceWithMarker(String marker) {
    Invoice inv = new Invoice();
    inv.setId("INV-0001");

    SchemeID endpoint = new SchemeID();
    endpoint.setValue(marker);
    endpoint.setSchemeID("0225");

    Party customerParty = new Party();
    customerParty.setEndpointId(endpoint);
    PartyLegalEntity ple = new PartyLegalEntity();
    SchemeID companyId = new SchemeID();
    companyId.setValue("123456789");
    ple.setCompanyId(companyId);
    customerParty.setPartyLegalEntity(ple);
    AccountingCustomerParty customer = new AccountingCustomerParty();
    customer.setParty(customerParty);
    inv.setAccountingCustomerParty(customer);

    Party supplierParty = new Party();
    PartyLegalEntity sple = new PartyLegalEntity();
    SchemeID sCompanyId = new SchemeID();
    sCompanyId.setValue("987654321");
    sple.setCompanyId(sCompanyId);
    supplierParty.setPartyLegalEntity(sple);
    AccountingSupplierParty supplier = new AccountingSupplierParty();
    supplier.setParty(supplierParty);
    inv.setAccountingSupplierParty(supplier);

    CodedValue currency = new CodedValue();
    currency.setValue("EUR");
    inv.setDocumentCurrencyCode(currency);
    return inv;
  }

  private static ExtractedAttachment pdf() {
    return new ExtractedAttachment("invoice.pdf", new byte[] {0x25, 0x50, 0x44, 0x46, 1, 2},
        "application/pdf");
  }

  private static ExtractedAttachment tradeCsv() {
    return new ExtractedAttachment("trades.csv", "a,b,c\n1,2,3\n".getBytes(), "text/csv");
  }

  // ── Tests ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("rule 1: a duplicate provider reference is CANCELLED + REFUSED(DOUBLON)")
  void duplicateInvoiceIsRefused() {
    Harness h = harness(markRules(/*duplicateExists*/ true));
    // CUSTODY with an attachment: only the duplicate rule should fire.
    RegistrationOutcome outcome = h.service()
        .register(invoiceWithMarker("552120222_MARK_CUSTODY"), List.of(pdf()));

    assertEquals(RegistrationOutcome.Status.CANCELLED, outcome.status());
    assertEquals(LifecycleEventType.REFUSED, outcome.lifecycleEvent());
    assertEquals("DOUBLON", outcome.lifecycleReasonCode());
    assertTrue(outcome.comment().contains("invoice already exists"));

    assertEquals(1, h.publisher().events.size(), "a refusal must queue a lifecycle event");
    assertEquals(42L, h.publisher().events.get(0).invoicePayableId());
    assertEquals(1, h.notifier().alerts.size(), "one comprehensive alert per failed invoice");
  }

  @Test
  @DisplayName("rule 2: no attachment anywhere is CANCELLED + SUSPENDED(JUSTIF_ABS)")
  void missingAttachmentIsSuspended() {
    Harness h = harness(markRules(false));
    RegistrationOutcome outcome = h.service()
        .register(invoiceWithMarker("552120222_MARK_CUSTODY"), List.of());

    assertTrue(hasCode(outcome, ErrorCode.MISSING_ATTACHMENT));
    assertEquals(RegistrationOutcome.Status.CANCELLED, outcome.status());
    assertEquals(LifecycleEventType.SUSPENDED, outcome.lifecycleEvent());
    assertEquals("JUSTIF_ABS", outcome.lifecycleReasonCode());
    assertEquals(1, h.publisher().events.size());
  }

  @Test
  @DisplayName("rule 3: BROKERAGE_PRINCIPAL without a .csv/.xlsx trade file is SUSPENDED")
  void brokerageWithoutTradeFileIsSuspended() {
    Harness h = harness(markRules(false));
    // A PDF is present, so rule 2 passes; the trade-file rule is what fires.
    RegistrationOutcome outcome = h.service()
        .register(invoiceWithMarker("552120222_MARK_BROKERAGE_PRINCIPAL"), List.of(pdf()));

    assertTrue(hasCode(outcome, ErrorCode.MISSING_TRADE_FILE));
    assertEquals(LifecycleEventType.SUSPENDED, outcome.lifecycleEvent());
  }

  @Test
  @DisplayName("rule 3 passes when a trade file IS supplied")
  void brokerageWithTradeFilePasses() {
    Harness h = harness(markRules(false));
    RegistrationOutcome outcome = h.service()
        .register(invoiceWithMarker("552120222_MARK_BROKERAGE_PRINCIPAL"),
            List.of(pdf(), tradeCsv()));

    assertTrue(!hasCode(outcome, ErrorCode.MISSING_TRADE_FILE),
        "a .csv trade file satisfies the brokerage requirement");
  }

  @Test
  @DisplayName("rule 4: CUSTODY with no line items → INCOMPLETE, alert but NO lifecycle event")
  void noLineItemsIsIncompleteAndAlertOnly() {
    Harness h = harness(markRules(false));
    RegistrationOutcome outcome = h.service()
        .register(invoiceWithMarker("552120222_MARK_CUSTODY"), List.of(pdf()));

    assertTrue(hasCode(outcome, ErrorCode.EMPTY_LINE_ITEMS));
    assertEquals(RegistrationOutcome.Status.INCOMPLETE, outcome.status(),
        "users add the missing lines later — CANCELLED would block that path");
    assertNull(outcome.lifecycleEvent());
    assertEquals(0, h.publisher().events.size(), "INCOMPLETE queues no lifecycle event");
    assertEquals(1, h.notifier().alerts.size(), "but ops is still told");
  }

  @Test
  @DisplayName("unknown business → BUSINESS_UNKNOWN, and no rules run for it")
  void unknownBusinessIsCaptured() {
    Harness h = harness(markRules(false));
    RegistrationOutcome outcome = h.service()
        .register(invoiceWithMarker("552120222_NOPE_CUSTODY"), List.of());

    assertTrue(hasCode(outcome, ErrorCode.BUSINESS_UNKNOWN));
    assertEquals(LifecycleEventType.REFUSED, outcome.lifecycleEvent());
    // MARK's rules must NOT have run — no attachment was supplied, yet MISSING_ATTACHMENT
    // should be absent because the business never resolved.
    assertTrue(!hasCode(outcome, ErrorCode.MISSING_ATTACHMENT),
        "rules are scoped per business; an unresolved business runs none of them");
  }

  @Test
  @DisplayName("unresolvable fee type is captured as FEETYPE_UNRESOLVED")
  void unresolvableFeeTypeIsCaptured() {
    Harness h = harness(markRules(false));
    // A bare BROKERAGE ties against BROKERAGE_PRINCIPAL and BROKERAGE_AGENCY → ambiguous.
    RegistrationOutcome outcome = h.service()
        .register(invoiceWithMarker("552120222_MARK_BROKERAGE"), List.of(pdf()));

    assertTrue(hasCode(outcome, ErrorCode.FEETYPE_UNRESOLVED),
        "the matcher refuses to guess between two equally-scoring entries");
    assertEquals(LifecycleEventType.REFUSED, outcome.lifecycleEvent());
  }

  @Test
  @DisplayName("resolved fee type is seeded onto the persisted row")
  void resolvedFeeTypeReachesPersistence() {
    Harness h = harness(markRules(false));
    h.service().register(invoiceWithMarker("552120222_MARK_CUSTODY"), List.of(pdf()));

    InvoicePayableStore.PersistRequest req = h.store().last.get();
    assertNotNull(req);
    assertEquals("F01", req.feeId(), "the referential id must land on the row");
    assertEquals("CUSTODY", req.feeType());
    assertEquals(Business.MARK, req.business());
    assertEquals("EINVOICE", req.source(), "this pipeline is e-invoice only");
  }

  @Test
  @DisplayName("a row is always persisted, even for a total failure")
  void rowIsAlwaysPersisted() {
    Harness h = harness(markRules(true));
    h.service().register(invoiceWithMarker("garbage-with-no-underscores"), List.of());
    assertNotNull(h.store().last.get(),
        "a failed registration is a data point, not a discard");
  }

  private static boolean hasCode(RegistrationOutcome outcome, ErrorCode code) {
    for (MappingError e : outcome.errors()) {
      if (e.code() == code) return true;
    }
    return false;
  }
}
