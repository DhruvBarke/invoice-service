package com.sg.domain.einvoice;

import com.sg.domain.einvoice.InvoiceRegistrationServiceImpl;
import com.sg.domain.einvoice.rule.ValidationRegistry;
import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.model.einvoice.EInvoiceMarker;
import com.sg.domaininterface.model.einvoice.error.ErrorCode;
import com.sg.domaininterface.model.einvoice.error.MappingError;
import com.sg.domaininterface.model.einvoice.error.RegistrationOutcome;
import com.sg.domaininterface.model.invoice.ExtractedAttachment;
import com.sg.domaininterface.model.invoice.Invoice;
import com.sg.domaininterface.model.payableinvoice.InvoiceItem;
import com.sg.domaininterface.model.payableinvoice.InvoicePayable;
import com.sg.domaininterface.model.payableinvoice.InvoicePayableModel;
import com.sg.domaininterface.port.in.InvoiceRegistrationService;
import com.sg.domaininterface.port.out.EInvoiceMappingPort.MappingResult;
import com.sg.domaininterface.port.out.EInvoiceMappingPort;
import com.sg.domaininterface.port.out.InvoicePayableStore;
import com.sg.domaininterface.port.out.LifecycleEventPublisher;
import com.sg.domaininterface.port.out.RegistrationAlertNotifier;
import com.sg.domaininterface.rule.einvoice.AttachmentChannel;
import com.sg.domaininterface.rule.einvoice.ValidationContext;
import com.sg.domaininterface.rule.einvoice.ValidationRule;
import java.util.ArrayList;
import com.sg.domaininterface.port.thirdparty.SgDocReferentialService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registration use case, driven through a stub {@link EInvoiceMappingPort}.
 *
 * <p>No mapper here on purpose. The point of the port is that orchestration can be exercised
 * without a mapping stack behind it: these tests state what the use case does with whatever
 * mapping reports, which is a different question from whether mapping reports the right thing.
 * The wired end-to-end tests live in the composition root, where the real adapter is assembled.
 *
 * <p>That this file needs no referential, no database and no framework is the property the
 * module's enforcer rule protects. If a change makes the use case need one, this stops
 * compiling.
 */
class InvoiceRegistrationServiceTest {

  private static final UUID ROW_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

  // ── Doubles ───────────────────────────────────────────────────────────────

  /** Returns whatever it was handed. */
  private static EInvoiceMappingPort port(MappingResult result) {
    return invoice -> result;
  }

  private static final class RecordingStore implements InvoicePayableStore {
    final AtomicReference<PersistRequest> last = new AtomicReference<>();
    int calls;

    @Override public UUID persist(PersistRequest request) {
      last.set(request);
      calls++;
      return ROW_ID;
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

  // ── Fixtures ──────────────────────────────────────────────────────────────

  private static Invoice invoice(String id) {
    Invoice inv = new Invoice();
    inv.setId(id);
    return inv;
  }

  private static InvoicePayableModel model() {
    InvoicePayableModel m = new InvoicePayableModel();
    InvoicePayable p = new InvoicePayable();
    p.setProviderReference("SUP-1");
    m.setInvoicePayable(p);
    return m;
  }

  private static ExtractedAttachment file(String name) {
    return new ExtractedAttachment(name, new byte[] {1}, "application/pdf");
  }

  private static MappingResult clean(List<ExtractedAttachment> embedded) {
    return new MappingResult(model(), List.of(new InvoiceItem()), embedded,
        new EInvoiceMarker("552120222", Business.MARK, "CUSTODY", "552120222_MARK_CUSTODY"),
        "F01", "CUSTODY", List.of());
  }

  private static ValidationRegistry noRules() {
    return ValidationRegistry.builder().build();
  }

  private record Harness(InvoiceRegistrationService service, RecordingStore store,
                         RecordingPublisher publisher, RecordingNotifier notifier) {}

  /** Accepts everything and numbers the handles, so a test can assert which upload was which. */
  private static final class RecordingDocumentStore implements SgDocReferentialService {
    final List<String> uploaded = new ArrayList<>();
    final AtomicInteger next = new AtomicInteger();

    @Override
    public String upload(ExtractedAttachment attachment, String invoiceReference) {
      uploaded.add(attachment.filename() + "@" + invoiceReference);
      return "DOC-" + next.incrementAndGet();
    }

    @Override
    public ExtractedAttachment download(String sgDocId) {
      throw new UnsupportedOperationException("registration never reads a document back");
    }
  }

  private static SgDocReferentialService refusingDocumentStore(RuntimeException failure) {
    return new SgDocReferentialService() {
      @Override public String upload(ExtractedAttachment a, String ref) { throw failure; }
      @Override public ExtractedAttachment download(String id) { return null; }
    };
  }

  private static Harness harness(MappingResult result, ValidationRegistry rules) {
    RecordingStore store = new RecordingStore();
    RecordingPublisher publisher = new RecordingPublisher();
    RecordingNotifier notifier = new RecordingNotifier();
    return new Harness(
        new InvoiceRegistrationServiceImpl(
            port(result), new RecordingDocumentStore(), rules, store, publisher, notifier),
        store, publisher, notifier);
  }

  // ── Happy path ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("a clean mapping with no rules registers, persists once and alerts nobody")
  void cleanRegistration() {
    Harness h = harness(clean(List.of()), noRules());

    RegistrationOutcome outcome = h.service().register(invoice("INV-1"), List.of());

    assertEquals(RegistrationOutcome.Status.REGISTERED, outcome.status());
    assertFalse(outcome.hasErrors());
    assertEquals(1, h.store().calls, "the row is always written");
    assertTrue(h.publisher().events.isEmpty(), "nothing went wrong, so nothing to report back");
    assertTrue(h.notifier().alerts.isEmpty(), "a clean registration is not worth an email");
  }

  @Test
  @DisplayName("the resolved fee identity and the flow reach the persist request")
  void feeIdentityAndFlowArePersisted() {
    Harness h = harness(clean(List.of()), noRules());
    h.service().register(invoice("INV-1"), List.of());

    InvoicePayableStore.PersistRequest req = h.store().last.get();
    assertEquals("F01", req.feeId());
    assertEquals("CUSTODY", req.feeType());
    assertEquals(Business.MARK, req.business());
    assertEquals(InvoiceRegistrationService.FLOW_EINVOICE, req.invoiceFlow(),
        "invoice_flow is what tells this row apart from a manual or SGAi one");
  }

  // ── Attachments ───────────────────────────────────────────────────────────

  @Nested
  @DisplayName("attachment channel")
  class Attachments {

    @Test
    @DisplayName("an upload supersedes the copy embedded in the document")
    void uploadWins() {
      Harness h = harness(clean(List.of(file("embedded.pdf"))), noRules());

      h.service().register(invoice("INV-1"), List.of(file("uploaded.pdf")));

      List<?> docs = h.store().last.get().documents();
      assertEquals(1, docs.size(), "the embedded copy is ignored, not merged");
      assertEquals("uploaded.pdf",
          h.store().last.get().documents().get(0).getDocumentName());
      assertEquals(AttachmentChannel.MULTIPART.name(),
          h.store().last.get().documents().get(0).getIncomingLine());
    }

    @Test
    @DisplayName("with no upload, the embedded copy is used and labelled as such")
    void embeddedIsTheFallback() {
      Harness h = harness(clean(List.of(file("embedded.pdf"))), noRules());

      h.service().register(invoice("INV-1"), List.of());

      assertEquals(1, h.store().last.get().documents().size());
      assertEquals(AttachmentChannel.EINVOICE_BODY.name(),
          h.store().last.get().documents().get(0).getIncomingLine(),
          "labelled as the document's own, so nobody is told a file was uploaded when none was");
    }

    @Test
    @DisplayName("an empty upload list is not an upload")
    void emptyUploadFallsBack() {
      Harness h = harness(clean(List.of(file("embedded.pdf"))), noRules());

      h.service().register(invoice("INV-1"), List.of());
      assertEquals(AttachmentChannel.EINVOICE_BODY.name(),
          h.store().last.get().documents().get(0).getIncomingLine());

      Harness h2 = harness(clean(List.of(file("embedded.pdf"))), noRules());
      h2.service().register(invoice("INV-1"), null);
      assertEquals(AttachmentChannel.EINVOICE_BODY.name(),
          h2.store().last.get().documents().get(0).getIncomingLine(),
          "a null list means the same thing an empty one does");
    }

    @Test
    @DisplayName("the winning channel is what the rules see")
    void rulesSeeTheWinningChannel() {
      AtomicReference<ValidationContext> seen = new AtomicReference<>();
      ValidationRegistry rules = ValidationRegistry.builder()
          .add(Business.MARK, new ValidationRule() {
            @Override public String id() { return "capture"; }
            @Override public List<MappingError> check(ValidationContext ctx) {
              seen.set(ctx);
              return List.of();
            }
          })
          .build();

      harness(clean(List.of(file("embedded.pdf"))), rules)
          .service().register(invoice("INV-1"), List.of(file("uploaded.pdf")));

      assertEquals(AttachmentChannel.MULTIPART, seen.get().channel());
      assertEquals(1, seen.get().attachments().size());
      assertEquals("uploaded.pdf", seen.get().attachments().get(0).filename());
    }
  }

  // ── Errors from mapping ───────────────────────────────────────────────────

  @Nested
  @DisplayName("errors")
  class Errors {

    @Test
    @DisplayName("mapping errors survive into the outcome, the row and the alert")
    void mappingErrorsPropagate() {
      MappingResult failed = new MappingResult(null, List.of(), List.of(),
          EInvoiceMarker.empty(), null, null,
          List.of(MappingError.of(ErrorCode.BUSINESS_UNKNOWN, "no business token")));
      Harness h = harness(failed, noRules());

      RegistrationOutcome outcome = h.service().register(invoice("INV-1"), List.of());

      assertTrue(outcome.hasErrors());
      assertEquals(ErrorCode.BUSINESS_UNKNOWN, outcome.errors().get(0).code());
      assertEquals(1, h.store().calls, "a failed registration is a data point, not a discard");
      assertEquals(1, h.notifier().alerts.size());
    }

    @Test
    @DisplayName("mapping and rule errors arrive in one alert, not two")
    void oneAlertCarriesEverything() {
      MappingResult withError = new MappingResult(model(), List.of(), List.of(),
          new EInvoiceMarker("552120222", Business.MARK, "CUSTODY", "raw"), null, "CUSTODY",
          List.of(MappingError.of(ErrorCode.FEETYPE_UNRESOLVED, "no match")));

      ValidationRegistry rules = ValidationRegistry.builder()
          .add(Business.MARK, new ValidationRule() {
            @Override public String id() { return "always-fails"; }
            @Override public List<MappingError> check(ValidationContext ctx) {
              return List.of(MappingError.of(ErrorCode.MISSING_ATTACHMENT, "nothing attached"));
            }
          })
          .build();

      Harness h = harness(withError, rules);
      RegistrationOutcome outcome = h.service().register(invoice("INV-1"), List.of());

      assertEquals(2, outcome.errors().size(), "both defects are on the one outcome");
      assertEquals(1, h.notifier().alerts.size(),
          "one invoice earns one email however many things were wrong with it");
      assertEquals(2, h.notifier().alerts.get(0).outcome().errors().size());
    }

    @Test
    @DisplayName("a mapping port that throws is recorded, not propagated")
    void throwingPortIsCaptured() {
      EInvoiceMappingPort exploding = inv -> {
        throw new IllegalStateException("adapter blew up");
      };
      RecordingStore store = new RecordingStore();
      RegistrationOutcome outcome = new InvoiceRegistrationServiceImpl(
          exploding, new RecordingDocumentStore(), noRules(), store, new RecordingPublisher(), new RecordingNotifier())
          .register(invoice("INV-1"), List.of());

      assertTrue(outcome.errors().stream().anyMatch(
          e -> e.code() == ErrorCode.MAPPING_ERROR
              && e.detail().contains("mapping port threw unexpectedly")));
      assertEquals(1, store.calls, "the registration is still recorded as failed");
    }

    @Test
    @DisplayName("a rule that throws becomes an error rather than sinking the run")
    void throwingRuleIsCaptured() {
      ValidationRegistry rules = ValidationRegistry.builder()
          .add(Business.MARK, new ValidationRule() {
            @Override public String id() { return "explodes"; }
            @Override public List<MappingError> check(ValidationContext ctx) {
              throw new IllegalStateException("rule is broken");
            }
          })
          .build();

      RegistrationOutcome outcome =
          harness(clean(List.of()), rules).service().register(invoice("INV-1"), List.of());

      assertTrue(outcome.errors().stream().anyMatch(
          e -> e.code() == ErrorCode.MAPPING_ERROR
              && e.detail().contains("rule 'explodes' threw unexpectedly")));
    }

    @Test
    @DisplayName("a rule returning null is treated as no findings")
    void nullRuleResultIsTolerated() {
      ValidationRegistry rules = ValidationRegistry.builder()
          .add(Business.MARK, new ValidationRule() {
            @Override public String id() { return "returns-null"; }
            @Override public List<MappingError> check(ValidationContext ctx) { return null; }
          })
          .build();

      assertFalse(harness(clean(List.of()), rules)
          .service().register(invoice("INV-1"), List.of()).hasErrors());
    }
  }

  // ── Lifecycle and alerting ────────────────────────────────────────────────

  @Nested
  @DisplayName("lifecycle")
  class Lifecycle {

    private MappingResult refusing() {
      return new MappingResult(model(), List.of(), List.of(),
          new EInvoiceMarker("552120222", Business.MARK, "CUSTODY", "raw"), "F01", "CUSTODY",
          List.of(MappingError.of(ErrorCode.DUPLICATE_INVOICE, "already registered")));
    }

    @Test
    @DisplayName("a refusing outcome queues the event with the row id")
    void refusalIsQueued() {
      Harness h = harness(refusing(), noRules());
      h.service().register(invoice("INV-1"), List.of());

      assertEquals(1, h.publisher().events.size());
      LifecycleEventPublisher.PendingLifecycleEvent e = h.publisher().events.get(0);
      assertEquals(ROW_ID, e.invoicePayableId());
      assertEquals("INV-1", e.invoiceReference(),
          "the peer is quoted its own reference, not ours");
      assertNotNull(e.reasonCode());
    }

    @Test
    @DisplayName("a publisher failure lands in the alert instead of unwinding the row")
    void publisherFailureIsCaptured() {
      RecordingStore store = new RecordingStore();
      RecordingNotifier notifier = new RecordingNotifier();
      RegistrationOutcome outcome = new InvoiceRegistrationServiceImpl(
          port(refusing()), new RecordingDocumentStore(), noRules(), store,
          e -> { throw new IllegalStateException("lifecycle store is down"); },
          notifier).register(invoice("INV-1"), List.of());

      assertEquals(1, store.calls, "the row was already committed");
      assertTrue(outcome.errors().stream().anyMatch(
          e -> e.detail().contains("lifecycle publisher failed")));
    }

    @Test
    @DisplayName("a notifier failure never fails the registration")
    void notifierFailureIsSwallowed() {
      RecordingStore store = new RecordingStore();
      RegistrationOutcome outcome = new InvoiceRegistrationServiceImpl(
          port(refusing()), new RecordingDocumentStore(), noRules(), store,
          new RecordingPublisher(),
          a -> { throw new IllegalStateException("SMTP is down"); })
          .register(invoice("INV-1"), List.of());

      assertNotNull(outcome, "the caller still gets its answer");
      assertEquals(1, store.calls);
    }
  }

  // ── Guards ────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("guards")
  class Guards {

    @Test
    @DisplayName("every collaborator is mandatory")
    void collaboratorsAreMandatory() {
      EInvoiceMappingPort p = port(clean(List.of()));
      ValidationRegistry r = noRules();
      RecordingStore s = new RecordingStore();
      RecordingPublisher pub = new RecordingPublisher();
      RecordingNotifier n = new RecordingNotifier();

      SgDocReferentialService d = new RecordingDocumentStore();

      assertThrows(NullPointerException.class,
          () -> new InvoiceRegistrationServiceImpl(null, d, r, s, pub, n));
      assertThrows(NullPointerException.class,
          () -> new InvoiceRegistrationServiceImpl(p, null, r, s, pub, n));
      assertThrows(NullPointerException.class,
          () -> new InvoiceRegistrationServiceImpl(p, d, null, s, pub, n));
      assertThrows(NullPointerException.class,
          () -> new InvoiceRegistrationServiceImpl(p, d, r, null, pub, n));
      assertThrows(NullPointerException.class,
          () -> new InvoiceRegistrationServiceImpl(p, d, r, s, null, n));
      assertThrows(NullPointerException.class,
          () -> new InvoiceRegistrationServiceImpl(p, d, r, s, pub, null));
    }

    @Test
    @DisplayName("a null invoice is rejected before anything reads it")
    void nullInvoiceRejected() {
      Harness h = harness(clean(List.of()), noRules());
      assertThrows(NullPointerException.class, () -> h.service().register(null, List.of()));
      assertEquals(0, h.store().calls, "nothing is written for a request that never was one");
    }
  }

  // ── The port's own result type ────────────────────────────────────────────

  @Nested
  @DisplayName("MappingResult")
  class Result {

    @Test
    @DisplayName("null collections normalise, so no caller null-checks them")
    void nullCollectionsNormalise() {
      MappingResult r = new MappingResult(
          null, null, null, EInvoiceMarker.empty(), null, null, null);
      assertTrue(r.items().isEmpty());
      assertTrue(r.embeddedAttachments().isEmpty());
      assertTrue(r.errors().isEmpty());
    }

    @Test
    @DisplayName("the marker is mandatory — an unreadable one is empty(), never null")
    void markerIsMandatory() {
      assertThrows(NullPointerException.class,
          () -> new MappingResult(null, List.of(), List.of(), null, null, null, List.of()));

      EInvoiceMarker empty = EInvoiceMarker.empty();
      assertNull(empty.business());
      assertNull(empty.feeType());
      assertNull(empty.siren());
      assertNull(empty.rawValue());
    }

    @Test
    @DisplayName("the lists are defensive copies")
    void listsAreCopied() {
      List<MappingError> mutable =
          new ArrayList<>(List.of(MappingError.of(ErrorCode.MAPPING_ERROR, "one")));
      MappingResult r = new MappingResult(
          null, List.of(), List.of(), EInvoiceMarker.empty(), null, null, mutable);
      mutable.clear();
      assertEquals(1, r.errors().size(), "the result does not change under its caller");
    }

    @Test
    @DisplayName("an unmatched fee token is still carried, so the row records what was sent")
    void rawFeeTokenSurvives() {
      Harness h = harness(new MappingResult(model(), List.of(), List.of(),
          new EInvoiceMarker("552120222", Business.MARK, "WEIRD_TOKEN", "raw"),
          null, "WEIRD_TOKEN", List.of()), noRules());

      h.service().register(invoice("INV-1"), List.of());

      assertNull(h.store().last.get().feeId(), "nothing matched, so there is no id");
      assertEquals("WEIRD_TOKEN", h.store().last.get().feeType(),
          "but the sender's own token is kept rather than the column going blank");
    }
  }

  @Nested
  @DisplayName("PersistRequest")
  class Persisting {

    private InvoicePayableStore.PersistRequest request(String flow, List<InvoiceItem> items) {
      return new InvoicePayableStore.PersistRequest(
          Business.MARK, "F01", "CUSTODY", flow, model(), items, null,
          RegistrationOutcome.decide(List.of()));
    }

    @Test
    @DisplayName("an absent flow defaults rather than writing a null discriminator")
    void flowDefaults() {
      // invoice_flow is how a reader tells this row from a manual or SGAi one. A null there
      // would make the row unattributable, so blank and null both become the real value.
      assertEquals("EINVOICE", request(null, List.of()).invoiceFlow());
      assertEquals("EINVOICE", request("   ", List.of()).invoiceFlow());
      assertEquals("EINVOICE", request("", List.of()).invoiceFlow());
      assertEquals("MANUAL", request("MANUAL", List.of()).invoiceFlow(),
          "a caller that names a flow keeps it — the default is a fallback, not an override");
    }

    @Test
    @DisplayName("null collections normalise and the copies are defensive")
    void collectionsNormalise() {
      assertTrue(request("EINVOICE", null).items().isEmpty());
      assertTrue(request("EINVOICE", null).documents().isEmpty());

      List<InvoiceItem> mutable = new ArrayList<>(List.of(new InvoiceItem()));
      InvoicePayableStore.PersistRequest req = request("EINVOICE", mutable);
      mutable.clear();
      assertEquals(1, req.items().size(), "the request does not change under its caller");
    }

    @Test
    @DisplayName("the outcome is mandatory — there is no row without a verdict")
    void outcomeIsMandatory() {
      assertThrows(NullPointerException.class, () -> new InvoicePayableStore.PersistRequest(
          Business.MARK, "F01", "CUSTODY", "EINVOICE", model(), List.of(), List.of(), null));
    }
  }

  @Test
  @DisplayName("the same model instance travels to the store, so minted ids reach the caller")
  void modelIsPassedByReference() {
    MappingResult result = clean(List.of());
    Harness h = harness(result, noRules());

    h.service().register(invoice("INV-1"), List.of());

    assertSame(result.model(), h.store().last.get().model(),
        "the store writes the reference it mints back onto this instance");
  }
}
