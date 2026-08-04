package com.sg.domaininterface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.model.einvoice.EInvoiceMarker;
import com.sg.domaininterface.model.einvoice.error.ErrorCode;
import com.sg.domaininterface.model.einvoice.error.MappingError;
import com.sg.domaininterface.model.einvoice.error.RegistrationOutcome;
import com.sg.domaininterface.model.invoice.ExtractedAttachment;
import com.sg.domaininterface.model.invoice.Invoice;
import com.sg.domaininterface.model.party.Flow;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.port.out.PartyRegistrationUnavailableException;
import com.sg.domaininterface.port.out.UnavailabilityReason;
import com.sg.domaininterface.port.out.GuardDecision;
import com.sg.domaininterface.model.payableinvoice.InvoiceDocumentPayable;
import com.sg.domaininterface.model.payableinvoice.InvoiceItem;
import com.sg.domaininterface.model.payableinvoice.InvoicePayable;
import com.sg.domaininterface.model.payableinvoice.InvoicePayableModel;
import com.sg.domaininterface.port.out.EInvoiceMappingPort.MappingResult;
import com.sg.domaininterface.port.out.InvoicePayableStore.PersistRequest;
import com.sg.domaininterface.port.out.LifecycleEventPublisher.PendingLifecycleEvent;
import com.sg.domaininterface.port.out.RegistrationAlertNotifier.RegistrationAlert;
import com.sg.domaininterface.port.out.AlertEmailPort;
import com.sg.domaininterface.rule.einvoice.AttachmentChannel;
import com.sg.domaininterface.rule.einvoice.ValidationContext;
import com.sg.domaininterface.rule.einvoice.ValidationRule;
import com.sg.domaininterface.rule.party.Anomaly;
import com.sg.domaininterface.rule.party.AnomalyType;
import com.sg.domaininterface.rule.party.DetectionPolicy;
import com.sg.domaininterface.rule.party.Servability;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The contracts this module declares, tested where they are declared.
 *
 * <p>These types are records and interfaces, but they are not empty: compact constructors reject
 * nulls, normalise absent collections and take defensive copies, and a couple of interfaces carry
 * default methods with real logic in them. Every one of those behaviours is something a consumer
 * relies on without checking — a caller passes null for a list precisely because the record
 * promises to turn it into an empty one.
 *
 * <p>They were previously exercised only through the modules that consume them, which meant this
 * module's own coverage looked thin while the rules were in fact being enforced. Testing them
 * here states the contract at its source, and it keeps working if a consumer stops using one.
 */
class ContractTypesTest {

  private static ExtractedAttachment file(String name) {
    return new ExtractedAttachment(name, new byte[] {1}, "application/pdf");
  }

  private static PartyRegistrationDetails party() {
    return new PartyRegistrationDetails(null, null, null, null, null, null,
        "G1", "Acme SA", "ACME", "123456789", "12345678900012", List.of());
  }

  private static InvoicePayableModel model() {
    InvoicePayableModel m = new InvoicePayableModel();
    m.setInvoicePayable(new InvoicePayable());
    return m;
  }

  // ── PersistRequest ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("PersistRequest")
  class Persisting {

    private PersistRequest request(String flow, List<InvoiceItem> items,
                                   List<InvoiceDocumentPayable> docs) {
      return new PersistRequest(Business.MARK, "F01", "CUSTODY", flow, model(), items, docs,
          RegistrationOutcome.decide(List.of()));
    }

    @Test
    @DisplayName("an absent flow defaults rather than writing a null discriminator")
    void flowDefaults() {
      // invoice_flow is how a reader tells this row from a manual or SGAi one. A null there
      // leaves the row unattributable, so blank and null both become the real value.
      assertEquals("EINVOICE", request(null, List.of(), List.of()).invoiceFlow());
      assertEquals("EINVOICE", request("", List.of(), List.of()).invoiceFlow());
      assertEquals("EINVOICE", request("   ", List.of(), List.of()).invoiceFlow());
      assertEquals("MANUAL", request("MANUAL", List.of(), List.of()).invoiceFlow(),
          "a caller that names a flow keeps it — the default is a fallback, not an override");
    }

    @Test
    @DisplayName("null collections normalise and the copies are defensive")
    void collectionsNormalise() {
      assertTrue(request("EINVOICE", null, null).items().isEmpty());
      assertTrue(request("EINVOICE", null, null).documents().isEmpty());

      List<InvoiceItem> items = new ArrayList<>(List.of(new InvoiceItem()));
      List<InvoiceDocumentPayable> docs =
          new ArrayList<>(List.of(new InvoiceDocumentPayable()));
      PersistRequest req = request("EINVOICE", items, docs);
      items.clear();
      docs.clear();

      assertEquals(1, req.items().size(), "the request does not change under its caller");
      assertEquals(1, req.documents().size());
    }

    @Test
    @DisplayName("the outcome is mandatory — there is no row without a verdict")
    void outcomeIsMandatory() {
      assertThrows(NullPointerException.class,
          () -> new PersistRequest(Business.MARK, "F01", "CUSTODY", "EINVOICE",
              model(), List.of(), List.of(), null));
    }
  }

  // ── MappingResult ─────────────────────────────────────────────────────────

  @Nested
  @DisplayName("MappingResult")
  class Mapping {

    @Test
    @DisplayName("null collections normalise, so no caller null-checks them")
    void nullCollectionsNormalise() {
      MappingResult r =
          new MappingResult(null, null, null, EInvoiceMarker.empty(), null, null, null);
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
      List<MappingError> errors =
          new ArrayList<>(List.of(MappingError.of(ErrorCode.MAPPING_ERROR, "one")));
      MappingResult r = new MappingResult(
          null, List.of(), List.of(), EInvoiceMarker.empty(), null, null, errors);
      errors.clear();
      assertEquals(1, r.errors().size(), "the result does not change under its caller");
    }
  }

  // ── ValidationContext ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("ValidationContext")
  class Context {

    private final EInvoiceMarker marker =
        new EInvoiceMarker("552120222", Business.MARK, "CUSTODY", "552120222_MARK_CUSTODY");

    @Test
    @DisplayName("null collections normalise and an unset channel means the document's own")
    void defaults() {
      ValidationContext c = new ValidationContext(
          Business.MARK, marker, new Invoice(), null, null, null, null);

      assertTrue(c.items().isEmpty());
      assertTrue(c.attachments().isEmpty());
      assertFalse(c.hasAnyAttachment());
      assertEquals(AttachmentChannel.EINVOICE_BODY, c.channel(),
          "the fallback channel, which is what an unspecified one means");
    }

    @Test
    @DisplayName("hasAnyAttachment reflects the winning channel, whichever it was")
    void hasAnyAttachment() {
      assertTrue(new ValidationContext(Business.MARK, marker, new Invoice(), null, List.of(),
          List.of(file("a.pdf")), AttachmentChannel.MULTIPART).hasAnyAttachment());
      assertTrue(new ValidationContext(Business.MARK, marker, new Invoice(), null, List.of(),
          List.of(file("b.pdf")), AttachmentChannel.EINVOICE_BODY).hasAnyAttachment());
      assertFalse(new ValidationContext(Business.MARK, marker, new Invoice(), null, List.of(),
          List.of(), AttachmentChannel.MULTIPART).hasAnyAttachment(),
          "an upload that carried no usable file is still an empty attachment set");
    }

    @Test
    @DisplayName("the source invoice and the marker are both mandatory")
    void mandatoryArguments() {
      assertThrows(NullPointerException.class, () -> new ValidationContext(
          Business.MARK, marker, null, null, List.of(), List.of(), AttachmentChannel.MULTIPART));
      assertThrows(NullPointerException.class, () -> new ValidationContext(
          Business.MARK, null, new Invoice(), null, List.of(), List.of(),
          AttachmentChannel.MULTIPART));
    }

    @Test
    @DisplayName("the attachment list is a defensive copy")
    void attachmentsAreCopied() {
      List<ExtractedAttachment> mutable = new ArrayList<>(List.of(file("a.pdf")));
      ValidationContext c = new ValidationContext(Business.MARK, marker, new Invoice(), null,
          List.of(), mutable, AttachmentChannel.MULTIPART);
      mutable.clear();
      assertEquals(1, c.attachments().size());
    }
  }

  // ── ValidationRule.id() ───────────────────────────────────────────────────

  @Nested
  @DisplayName("ValidationRule.id()")
  class RuleIds {

    /** The id is what the per-business config keys on, so its spelling is a contract. */
    private String idOf(ValidationRule rule) {
      return rule.id();
    }

    @Test
    @DisplayName("the class name becomes a kebab-case id with the Rule suffix dropped")
    void derivedFromClassName() {
      class DuplicateInvoiceRule implements ValidationRule {
        @Override public List<MappingError> check(ValidationContext ctx) { return List.of(); }
      }
      class AttachmentPresentRule implements ValidationRule {
        @Override public List<MappingError> check(ValidationContext ctx) { return List.of(); }
      }
      assertEquals("duplicate-invoice", idOf(new DuplicateInvoiceRule()));
      assertEquals("attachment-present", idOf(new AttachmentPresentRule()));
    }

    @Test
    @DisplayName("a name without the suffix keeps all of itself")
    void noSuffixToStrip() {
      class Brokerage implements ValidationRule {
        @Override public List<MappingError> check(ValidationContext ctx) { return List.of(); }
      }
      assertEquals("brokerage", idOf(new Brokerage()));
    }

    @Test
    @DisplayName("a single leading capital produces no leading dash")
    void noLeadingDash() {
      class XRule implements ValidationRule {
        @Override public List<MappingError> check(ValidationContext ctx) { return List.of(); }
      }
      assertEquals("x", idOf(new XRule()),
          "a leading dash would silently never match a configured id");
    }
  }

  // ── Party anomaly value types ─────────────────────────────────────────────

  @Nested
  @DisplayName("Anomaly")
  class Anomalies {

    @Test
    @DisplayName("type and detail are mandatory; the subject is not")
    void mandatoryFields() {
      assertThrows(NullPointerException.class,
          () -> new Anomaly(null, "detail", null));
      assertThrows(NullPointerException.class,
          () -> new Anomaly(AnomalyType.MISSING_SIRET, null, null));
      assertNull(Anomaly.of(AnomalyType.MISSING_SIRET, "detail").subject(),
          "an anomaly can describe an absence, which has no subject to point at");
    }

    @Test
    @DisplayName("the three-argument factory keeps the subject")
    void factoryWithSubject() {
      PartyRegistrationDetails party = party();
      assertSame(party,
          Anomaly.of(AnomalyType.MISSING_SIRET, "detail", party).subject());
    }

    @Test
    @DisplayName("one blocking anomaly makes the whole set unservable")
    void servabilityOfSet() {
      Anomaly blocking = Anomaly.of(AnomalyType.NO_REGISTRATION_FOUND, "none");
      Anomaly benign = Anomaly.of(AnomalyType.MISSING_SIRET, "absent");

      assertEquals(blocking.type().isBlocking(), blocking.isBlocking());
      assertEquals(Servability.SERVABLE, Anomaly.servabilityOf(List.of()),
          "nothing wrong means servable");

      Servability mixed = Anomaly.servabilityOf(List.of(benign, blocking));
      assertEquals(blocking.isBlocking() ? Servability.BLOCKING : Servability.SERVABLE, mixed,
          "the worst anomaly in the set decides, not the first or the last");
    }
  }

  @Test
  @DisplayName("DetectionPolicy: the defaults enable everything except the outbound advisory")
  void detectionPolicyPresets() {
    DetectionPolicy defaults = DetectionPolicy.defaults();
    assertTrue(defaults.inboundMissingSiret());
    assertTrue(defaults.outboundMissingSiret());
    assertTrue(defaults.inboundGoldenMismatch());
    assertFalse(defaults.outboundGoldenMismatch(),
        "the one advisory check that is off by default — turning it on quarantines outbound "
            + "parties on a mismatch that does not block serving them");

    DetectionPolicy mandatory = DetectionPolicy.mandatoryOnly();
    assertFalse(mandatory.inboundMissingSiret());
    assertFalse(mandatory.outboundMissingSiret());
    assertFalse(mandatory.inboundGoldenMismatch());
    assertFalse(mandatory.outboundGoldenMismatch());
  }

  // ── Remaining port value types ────────────────────────────────────────────

  @Test
  @DisplayName("a lifecycle event needs a type and a reason code")
  void lifecycleEventGuards() {
    assertThrows(NullPointerException.class, () -> new PendingLifecycleEvent(
        UUID.randomUUID(), "INV-1", null, "DOUBLON", "c", Instant.EPOCH));
    assertThrows(NullPointerException.class, () -> new PendingLifecycleEvent(
        UUID.randomUUID(), "INV-1",
        com.sg.domaininterface.model.einvoice.error.LifecycleEventType.REFUSED, null, "c",
        Instant.EPOCH));

    PendingLifecycleEvent defaulted = new PendingLifecycleEvent(
        UUID.randomUUID(), "INV-1",
        com.sg.domaininterface.model.einvoice.error.LifecycleEventType.REFUSED, "DOUBLON", "c",
        null);
    assertNotNull(defaulted.occurredAt(),
        "an absent timestamp defaults to now rather than staying null");

    PendingLifecycleEvent supplied = new PendingLifecycleEvent(
        UUID.randomUUID(), "INV-1",
        com.sg.domaininterface.model.einvoice.error.LifecycleEventType.SUSPENDED, "JUSTIF_ABS",
        "c", Instant.EPOCH);
    assertEquals(Instant.EPOCH, supplied.occurredAt(),
        "a caller that knows when it happened keeps its own answer — the default must not "
            + "overwrite a real detection time with the moment the record was built");
  }

  @Test
  @DisplayName("an alert needs an outcome to describe")
  void registrationAlertGuards() {
    assertThrows(NullPointerException.class, () -> new RegistrationAlert(
        UUID.randomUUID(), "INV-1", Business.MARK, EInvoiceMarker.empty(), null, Instant.EPOCH));

    assertThrows(NullPointerException.class, () -> new RegistrationAlert(
        UUID.randomUUID(), "INV-1", Business.MARK, null,
        RegistrationOutcome.decide(List.of()), Instant.EPOCH));

    RegistrationAlert defaulted = new RegistrationAlert(UUID.randomUUID(), "INV-1",
        Business.MARK, EInvoiceMarker.empty(), RegistrationOutcome.decide(List.of()), null);
    assertNotNull(defaulted.occurredAt(),
        "an absent timestamp defaults to now rather than staying null");

    RegistrationAlert supplied = new RegistrationAlert(UUID.randomUUID(), "INV-1", Business.MARK,
        EInvoiceMarker.empty(), RegistrationOutcome.decide(List.of()), Instant.EPOCH);
    assertEquals(Instant.EPOCH, supplied.occurredAt(), "a supplied time is kept");
  }

  @Test
  @DisplayName("a dispatch failure carries its cause")
  void emailDispatchException() {
    Throwable cause = new IllegalStateException("connection refused");
    AlertEmailPort.EmailDispatchException ex =
        new AlertEmailPort.EmailDispatchException("send failed", cause);
    assertEquals("send failed", ex.getMessage());
    assertSame(cause, ex.getCause());
  }

  @Test
  @DisplayName("DetectionPolicy asks a different question per flow")
  void detectionPolicyIsFlowSensitive() {
    // Inbound and outbound are configured independently on purpose: the same anomaly can be
    // worth quarantining on the way in and merely advisory on the way out. A policy that
    // ignored the flow would apply the stricter of the two to both.
    DetectionPolicy p = new DetectionPolicy(true, false, false, true);

    assertTrue(p.checkMissingSiret(Flow.INBOUND));
    assertFalse(p.checkMissingSiret(Flow.OUTBOUND));
    assertFalse(p.checkGoldenMismatch(Flow.INBOUND));
    assertTrue(p.checkGoldenMismatch(Flow.OUTBOUND));
  }

  @Test
  @DisplayName("GuardDecision normalises an absent record list")
  void guardDecisionNormalises() {
    assertTrue(GuardDecision.pass(null).records().isEmpty(),
        "a guard that decided to serve nothing still serves a list, not a null");
    assertEquals(1, GuardDecision.pass(List.of(party())).records().size());
  }

  @Test
  @DisplayName("retryability comes from the reason, and an absent reason is not retryable")
  void unavailabilityRetryability() {
    assertTrue(new PartyRegistrationUnavailableException(
        UnavailabilityReason.UPSTREAM_UNAVAILABLE, "SIREN", "123456789", "down").isRetryable(),
        "an upstream outage is worth retrying");
    assertFalse(new PartyRegistrationUnavailableException(
        UnavailabilityReason.NOT_FOUND, "SIREN", "123456789", "absent").isRetryable(),
        "a party that does not exist will not start existing on a retry");
    assertFalse(new PartyRegistrationUnavailableException(
        null, "SIREN", "123456789", "no reason given").isRetryable(),
        "an unclassified failure is not retried — retrying something nobody could name is how "
            + "a caller ends up hammering an endpoint that will never succeed");
  }

  @Test
  @DisplayName("AttachmentChannel names both sides of the request")
  void attachmentChannelValues() {
    assertEquals(2, AttachmentChannel.values().length);
    assertEquals(AttachmentChannel.MULTIPART, AttachmentChannel.valueOf("MULTIPART"));
    assertEquals(AttachmentChannel.EINVOICE_BODY, AttachmentChannel.valueOf("EINVOICE_BODY"));
  }
}
