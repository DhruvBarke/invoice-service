package com.example.invoice.service.registration.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The precedence rule is the single most consequential piece of logic in the pipeline: it
 * decides what status the row gets and which lifecycle event goes back to the peer. Getting
 * REFUSED vs SUSPENDED backwards means a recoverable invoice is terminally rejected, or a
 * garbage invoice sits waiting for a correction that can never fix it.
 */
class RegistrationOutcomeTest {

  @Test
  @DisplayName("no errors → REGISTERED, no lifecycle event")
  void cleanRunRegisters() {
    RegistrationOutcome o = RegistrationOutcome.decide(List.of());
    assertEquals(RegistrationOutcome.Status.REGISTERED, o.status());
    assertNull(o.lifecycleEvent());
    assertTrue(o.isRegistered());
  }

  @Test
  @DisplayName("REFUSED beats SUSPENDED when both are present")
  void refusedTakesPrecedenceOverSuspended() {
    RegistrationOutcome o = RegistrationOutcome.decide(List.of(
        MappingError.of(ErrorCode.MISSING_ATTACHMENT, "no attachment"),   // SUSPENDED
        MappingError.of(ErrorCode.DUPLICATE_INVOICE, "already exists")));  // REFUSED

    assertEquals(RegistrationOutcome.Status.CANCELLED, o.status());
    assertEquals(LifecycleEventType.REFUSED, o.lifecycleEvent(),
        "spec point 10: refusal lifecycle takes precedence over suspension");
    assertEquals("DOUBLON", o.lifecycleReasonCode());
    assertEquals(2, o.errors().size(), "both errors are still carried for the alert");
  }

  @Test
  @DisplayName("SUSPENDED wins when no REFUSED-class error fired")
  void suspendedWhenNoRefusal() {
    RegistrationOutcome o = RegistrationOutcome.decide(List.of(
        MappingError.of(ErrorCode.MISSING_TRADE_FILE, "no csv/xlsx")));

    assertEquals(RegistrationOutcome.Status.CANCELLED, o.status());
    assertEquals(LifecycleEventType.SUSPENDED, o.lifecycleEvent());
    assertEquals("JUSTIF_ABS", o.lifecycleReasonCode());
  }

  @Test
  @DisplayName("EMPTY_LINE_ITEMS alone → INCOMPLETE with NO lifecycle event")
  void emptyLineItemsIsIncompleteAndAlertOnly() {
    RegistrationOutcome o = RegistrationOutcome.decide(List.of(
        MappingError.of(ErrorCode.EMPTY_LINE_ITEMS, "no lines for CUSTODY")));

    assertEquals(RegistrationOutcome.Status.INCOMPLETE, o.status(),
        "users must be able to add the missing lines later — CANCELLED would block that");
    assertNull(o.lifecycleEvent(),
        "no lifecycle event: the sender's payload isn't refusable, the invoice is just unfinished");
    assertTrue(o.hasErrors(), "still alertable so ops knows the row is sitting there");
  }

  @Test
  @DisplayName("EMPTY_LINE_ITEMS alongside a refusal loses to the refusal")
  void emptyLineItemsLosesToRefusal() {
    RegistrationOutcome o = RegistrationOutcome.decide(List.of(
        MappingError.of(ErrorCode.EMPTY_LINE_ITEMS, "no lines"),
        MappingError.of(ErrorCode.FEETYPE_UNRESOLVED, "unknown fee type")));

    assertEquals(RegistrationOutcome.Status.CANCELLED, o.status());
    assertEquals(LifecycleEventType.REFUSED, o.lifecycleEvent());
    assertEquals("NON_CONFORME", o.lifecycleReasonCode());
  }

  @Test
  @DisplayName("comment carries the winning error's detail, for the row's comment column")
  void commentComesFromWinningError() {
    RegistrationOutcome o = RegistrationOutcome.decide(List.of(
        MappingError.of(ErrorCode.DUPLICATE_INVOICE, "invoice already exists (ref=INV-1)")));
    assertEquals("invoice already exists (ref=INV-1)", o.comment());
  }

  @Test
  @DisplayName("every ErrorCode with a lifecycle event also carries a reason code, and vice versa")
  void lifecycleAndReasonCodeAreConsistent() {
    for (ErrorCode c : ErrorCode.values()) {
      boolean hasLifecycle = c.lifecycleEvent() != null;
      boolean hasReason = c.reasonCode() != null;
      assertEquals(hasLifecycle, hasReason,
          c.name() + " must declare a reason code iff it declares a lifecycle event — the "
              + "scheduler needs both to build the outbound event");
    }
  }
}
