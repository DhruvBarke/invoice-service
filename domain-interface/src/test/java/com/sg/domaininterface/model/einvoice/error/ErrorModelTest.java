package com.sg.domaininterface.model.einvoice.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.model.einvoice.EInvoiceMarker;
import com.sg.domaininterface.model.einvoice.EInvoiceMarkerParser;
import com.sg.domaininterface.port.einvoice.LifecycleEventPublisher.PendingLifecycleEvent;
import com.sg.domaininterface.port.einvoice.RegistrationAlertNotifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The error taxonomy, the precedence rule, marker parsing and the port value types. */
class ErrorModelTest {

  // ── ErrorCode ─────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("ErrorCode taxonomy")
  class Codes {

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("every code carries a stable id and a description")
    void everyCodeIsDescribed(ErrorCode code) {
      assertNotNull(code.code());
      assertFalse(code.code().isBlank());
      assertNotNull(code.description());
      assertFalse(code.description().isBlank());
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("lifecycle event and reason code are declared together or not at all")
    void lifecycleAndReasonAgree(ErrorCode code) {
      if (code.lifecycleEvent() == null) {
        assertNull(code.reasonCode(),
            code + " fires no lifecycle event, so a reason code would have nowhere to go");
      } else {
        assertNotNull(code.reasonCode(),
            code + " fires a lifecycle event, which the peer cannot interpret without a reason");
      }
    }

    @Test
    @DisplayName("ids are unique — downstream systems key on them")
    void idsAreUnique() {
      Set<String> seen = new java.util.HashSet<>();
      for (ErrorCode c : ErrorCode.values()) {
        assertTrue(seen.add(c.code()), "duplicate error id: " + c.code());
      }
    }

    @Test
    @DisplayName("the reason codes match the einvoice-service seed vocabulary")
    void reasonCodesMatchSeededVocabulary() {
      Set<String> seeded = Set.of("DOUBLON", "NON_CONFORME", "SIRET_ERR", "JUSTIF_ABS",
          "REF_CT_ABSENT");
      for (ErrorCode c : ErrorCode.values()) {
        if (c.reasonCode() != null) {
          assertTrue(seeded.contains(c.reasonCode()),
              c + " uses reason code " + c.reasonCode()
                  + ", which is not in t_reason_code_status");
        }
      }
    }

    @Test
    @DisplayName("EMPTY_LINE_ITEMS is the one alert-only code")
    void emptyLineItemsIsAlertOnly() {
      assertNull(ErrorCode.EMPTY_LINE_ITEMS.lifecycleEvent(),
          "users complete the invoice in-app; refusing it would block that");
      List<ErrorCode> alertOnly = new ArrayList<>();
      for (ErrorCode c : ErrorCode.values()) {
        if (c.lifecycleEvent() == null) alertOnly.add(c);
      }
      assertEquals(List.of(ErrorCode.EMPTY_LINE_ITEMS), alertOnly);
    }

    @Test
    @DisplayName("the two lifecycle classes carry their CDAR codes")
    void lifecycleCdarCodes() {
      assertEquals(210, LifecycleEventType.REFUSED.cdarCode());
      assertEquals(208, LifecycleEventType.SUSPENDED.cdarCode());
      assertEquals(EnumSet.of(LifecycleEventType.REFUSED, LifecycleEventType.SUSPENDED),
          EnumSet.allOf(LifecycleEventType.class));
    }
  }

  // ── MappingError ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("MappingError")
  class Errors {

    @Test
    @DisplayName("the two-arg factory stamps a timestamp and leaves the cause null")
    void twoArgFactory() {
      MappingError e = MappingError.of(ErrorCode.DUPLICATE_INVOICE, "already here");
      assertEquals(ErrorCode.DUPLICATE_INVOICE, e.code());
      assertEquals("already here", e.detail());
      assertNull(e.cause());
      assertNotNull(e.detectedAt());
    }

    @Test
    @DisplayName("the three-arg factory keeps the cause for the alert's stack trace")
    void threeArgFactory() {
      RuntimeException boom = new IllegalStateException("boom");
      MappingError e = MappingError.of(ErrorCode.MAPPING_ERROR, "wrapped", boom);
      assertSame(boom, e.cause());
    }

    @Test
    @DisplayName("a null timestamp is filled in rather than stored")
    void nullTimestampIsFilledIn() {
      MappingError e = new MappingError(ErrorCode.MAPPING_ERROR, "d", null, null);
      assertNotNull(e.detectedAt());
    }

    @Test
    @DisplayName("code and detail are mandatory")
    void codeAndDetailMandatory() {
      Instant now = Instant.now();
      assertThrows(NullPointerException.class,
          () -> new MappingError(null, "d", null, now));
      assertThrows(NullPointerException.class,
          () -> new MappingError(ErrorCode.MAPPING_ERROR, null, null, now));
    }
  }

  // ── RegistrationOutcome precedence ────────────────────────────────────────

  @Nested
  @DisplayName("RegistrationOutcome.decide — precedence")
  class Precedence {

    @Test
    @DisplayName("no errors → REGISTERED with nothing to send")
    void noErrorsRegisters() {
      RegistrationOutcome o = RegistrationOutcome.decide(List.of());
      assertEquals(RegistrationOutcome.Status.REGISTERED, o.status());
      assertNull(o.lifecycleEvent());
      assertNull(o.lifecycleReasonCode());
      assertNull(o.comment());
      assertTrue(o.isRegistered());
      assertFalse(o.hasErrors());
    }

    @Test
    @DisplayName("a null error list is treated as no errors")
    void nullErrorsRegisters() {
      assertTrue(RegistrationOutcome.decide(null).isRegistered());
    }

    @Test
    @DisplayName("REFUSED beats SUSPENDED when both are present")
    void refusedBeatsSuspended() {
      RegistrationOutcome o = RegistrationOutcome.decide(List.of(
          MappingError.of(ErrorCode.MISSING_ATTACHMENT, "no file"),
          MappingError.of(ErrorCode.DUPLICATE_INVOICE, "invoice already exists")));

      assertEquals(LifecycleEventType.REFUSED, o.lifecycleEvent());
      assertEquals("DOUBLON", o.lifecycleReasonCode());
      assertEquals("invoice already exists", o.comment(),
          "the comment comes from the winning error, not the first one raised");
      assertEquals(2, o.errors().size(), "both errors are still reported to ops");
    }

    @Test
    @DisplayName("REFUSED beats an alert-only error too")
    void refusedBeatsAlertOnly() {
      RegistrationOutcome o = RegistrationOutcome.decide(List.of(
          MappingError.of(ErrorCode.EMPTY_LINE_ITEMS, "no lines"),
          MappingError.of(ErrorCode.MAPPING_ERROR, "broke")));
      assertEquals(RegistrationOutcome.Status.CANCELLED, o.status());
      assertEquals(LifecycleEventType.REFUSED, o.lifecycleEvent());
    }

    @Test
    @DisplayName("SUSPENDED beats an alert-only error")
    void suspendedBeatsAlertOnly() {
      RegistrationOutcome o = RegistrationOutcome.decide(List.of(
          MappingError.of(ErrorCode.EMPTY_LINE_ITEMS, "no lines"),
          MappingError.of(ErrorCode.MISSING_TRADE_FILE, "no trade file")));
      assertEquals(RegistrationOutcome.Status.CANCELLED, o.status());
      assertEquals(LifecycleEventType.SUSPENDED, o.lifecycleEvent());
      assertEquals("JUSTIF_ABS", o.lifecycleReasonCode());
    }

    @Test
    @DisplayName("an alert-only error alone → INCOMPLETE with no lifecycle event")
    void alertOnlyIsIncomplete() {
      RegistrationOutcome o = RegistrationOutcome.decide(List.of(
          MappingError.of(ErrorCode.EMPTY_LINE_ITEMS, "no lines")));
      assertEquals(RegistrationOutcome.Status.INCOMPLETE, o.status());
      assertNull(o.lifecycleEvent());
      assertNull(o.lifecycleReasonCode());
      assertEquals("no lines", o.comment());
      assertTrue(o.hasErrors());
      assertFalse(o.isRegistered());
    }

    @Test
    @DisplayName("the error list is defensively copied")
    void errorListIsImmutable() {
      List<MappingError> mutable = new ArrayList<>();
      mutable.add(MappingError.of(ErrorCode.MAPPING_ERROR, "one"));
      RegistrationOutcome o = RegistrationOutcome.decide(mutable);
      mutable.clear();
      assertEquals(1, o.errors().size(), "clearing the caller's list must not empty the outcome");
      assertThrows(UnsupportedOperationException.class,
          () -> o.errors().add(MappingError.of(ErrorCode.MAPPING_ERROR, "two")));
    }

    @Test
    @DisplayName("status is mandatory on the record")
    void statusMandatory() {
      assertThrows(NullPointerException.class,
          () -> new RegistrationOutcome(null, null, null, List.of(), null));
    }

    @Test
    @DisplayName("a null error list on the record normalises to empty")
    void recordNullErrorsNormalise() {
      RegistrationOutcome o =
          new RegistrationOutcome(RegistrationOutcome.Status.REGISTERED, null, null, null, null);
      assertTrue(o.errors().isEmpty());
    }
  }

  // ── Marker parsing ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("EInvoiceMarkerParser")
  class Markers {

    @Test
    @DisplayName("the canonical three-segment form splits cleanly")
    void canonicalForm() {
      EInvoiceMarker m = EInvoiceMarkerParser.parse("552120222_MARK_CUSTODY");
      assertEquals("552120222", m.siren());
      assertEquals(Business.MARK, m.business());
      assertEquals("CUSTODY", m.feeType());
      assertEquals("552120222_MARK_CUSTODY", m.rawValue());
    }

    @Test
    @DisplayName("only the first two underscores are separators")
    void feeTypeTailKeepsItsUnderscores() {
      EInvoiceMarker m = EInvoiceMarkerParser.parse("552120222_MARK_BROKERAGE_PRINCIPAL");
      assertEquals(Business.MARK, m.business());
      assertEquals("BROKERAGE_PRINCIPAL", m.feeType());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("absent input yields an all-null marker rather than throwing")
    void absentInput(String raw) {
      EInvoiceMarker m = EInvoiceMarkerParser.parse(raw);
      assertNull(m.siren());
      assertNull(m.business());
      assertNull(m.feeType());
    }

    @Test
    @DisplayName("no underscore at all — the whole value is treated as the siren")
    void noUnderscore() {
      EInvoiceMarker m = EInvoiceMarkerParser.parse("552120222");
      assertEquals("552120222", m.siren());
      assertNull(m.business());
      assertNull(m.feeType());
    }

    @Test
    @DisplayName("a leading underscore leaves nothing to key on")
    void leadingUnderscore() {
      EInvoiceMarker m = EInvoiceMarkerParser.parse("_MARK_CUSTODY");
      assertNull(m.business(), "there is no siren segment, so the value is not a valid marker");
    }

    @Test
    @DisplayName("business but no fee-type tail")
    void businessWithoutFeeType() {
      EInvoiceMarker m = EInvoiceMarkerParser.parse("552120222_MARK");
      assertEquals("552120222", m.siren());
      assertEquals(Business.MARK, m.business());
      assertNull(m.feeType());
    }

    @Test
    @DisplayName("a trailing underscore leaves an empty tail, reported as absent")
    void trailingUnderscore() {
      assertNull(EInvoiceMarkerParser.parse("552120222_MARK_").feeType());
    }

    @Test
    @DisplayName("an unknown business token parses structurally but resolves to null")
    void unknownBusinessToken() {
      EInvoiceMarker m = EInvoiceMarkerParser.parse("552120222_NOPE_CUSTODY");
      assertEquals("552120222", m.siren());
      assertNull(m.business());
      assertEquals("CUSTODY", m.feeType(), "the tail is still parsed, for the alert body");
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed")
    void trimsWhitespace() {
      assertEquals(Business.MARK,
          EInvoiceMarkerParser.parse("  552120222_MARK_CUSTODY  ").business());
    }
  }

  // ── Business ──────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("Business")
  class Businesses {

    @ParameterizedTest
    @EnumSource(Business.class)
    @DisplayName("every value round-trips through tryParse")
    void roundTrip(Business b) {
      assertEquals(b, Business.tryParse(b.name()).orElseThrow());
      assertEquals(b, Business.tryParse(b.name().toLowerCase()).orElseThrow());
      assertEquals(b, Business.parse(b.name()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "NOTABUSINESS"})
    @DisplayName("unknown tokens yield empty rather than a wrong answer")
    void unknownTokens(String token) {
      assertTrue(Business.tryParse(token).isEmpty());
    }

    @Test
    @DisplayName("parse throws where tryParse returns empty")
    void parseThrowsOnUnknown() {
      assertThrows(IllegalArgumentException.class, () -> Business.parse("NOTABUSINESS"));
      assertThrows(NullPointerException.class, () -> Business.parse(null));
    }
  }

  // ── Port value types ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("port value types")
  class PortTypes {

    @Test
    @DisplayName("PendingLifecycleEvent fills in a missing timestamp and demands the essentials")
    void pendingLifecycleEvent() {
      UUID rowId = UUID.randomUUID();
      PendingLifecycleEvent e = new PendingLifecycleEvent(
          rowId, "INV1", LifecycleEventType.REFUSED, "DOUBLON", "dup", null);
      assertNotNull(e.occurredAt());

      // The row id is the only way back to the invoice this event belongs to. An event with
      // none could be queued but never matched to anything, so it is rejected at construction.
      assertThrows(NullPointerException.class, () -> new PendingLifecycleEvent(
          null, "INV1", LifecycleEventType.REFUSED, "DOUBLON", "c", Instant.now()));
      assertThrows(NullPointerException.class, () -> new PendingLifecycleEvent(
          rowId, "INV1", null, "DOUBLON", "c", Instant.now()));
      assertThrows(NullPointerException.class, () -> new PendingLifecycleEvent(
          rowId, "INV1", LifecycleEventType.REFUSED, null, "c", Instant.now()));
    }

    @Test
    @DisplayName("RegistrationAlert exposes the outcome's errors and fills its timestamp")
    void registrationAlert() {
      RegistrationOutcome outcome = RegistrationOutcome.decide(List.of(
          MappingError.of(ErrorCode.DUPLICATE_INVOICE, "dup")));
      EInvoiceMarker marker = EInvoiceMarkerParser.parse("552120222_MARK_CUSTODY");

      RegistrationAlertNotifier.RegistrationAlert alert =
          new RegistrationAlertNotifier.RegistrationAlert(
              UUID.randomUUID(), "INV1", Business.MARK, marker, outcome, null);

      assertNotNull(alert.occurredAt());
      assertEquals(1, alert.errors().size());
      assertEquals(ErrorCode.DUPLICATE_INVOICE, alert.errors().get(0).code());

      assertThrows(NullPointerException.class,
          () -> new RegistrationAlertNotifier.RegistrationAlert(
              UUID.randomUUID(), "INV1", Business.MARK, marker, null, null));
      assertThrows(NullPointerException.class,
          () -> new RegistrationAlertNotifier.RegistrationAlert(
              UUID.randomUUID(), "INV1", Business.MARK, null, outcome, null));
    }

    @Test
    @DisplayName("the no-op notifier accepts anything without complaint")
    void noOpNotifier() {
      RegistrationAlertNotifier.none().notify(null);
    }
  }
}
