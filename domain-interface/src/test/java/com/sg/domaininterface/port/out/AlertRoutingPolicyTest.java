package com.sg.domaininterface.port.out;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.model.einvoice.error.RegistrationOutcome;
import com.sg.domaininterface.port.out.AlertRoutingPolicy.Route;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The routing contract.
 *
 * <p>{@link Route#shouldSend()} is the whole point of this type: it is the single question every
 * caller asks, and the two ways an alert can be suppressed — switched off, or configured with
 * nobody to send to — have to give the same answer or one of them leaks through to the transport.
 */
class AlertRoutingPolicyTest {

  @Nested
  @DisplayName("Route")
  class Routes {

    @Test
    @DisplayName("a route with recipients and enabled sends")
    void enabledWithRecipientsSends() {
      Route route = new Route(true, List.of("ops@example.com"), "[x]");
      assertTrue(route.shouldSend());
      assertEquals(List.of("ops@example.com"), route.recipients());
      assertEquals("[x]", route.subjectPrefix());
    }

    @Test
    @DisplayName("disabled does not send, whatever the recipients say")
    void disabledDoesNotSend() {
      assertFalse(new Route(false, List.of("ops@example.com"), "[x]").shouldSend());
    }

    @Test
    @DisplayName("enabled with nobody to tell does not send either")
    void noRecipientsDoesNotSend() {
      // Otherwise the message is built, handed to the transport and rejected for an empty To
      // header — which surfaces as a mail fault rather than as the configuration gap it is.
      assertFalse(new Route(true, List.of(), "[x]").shouldSend());
      assertFalse(new Route(true, null, "[x]").shouldSend());
    }

    @Test
    @DisplayName("nulls normalise, so no caller null-checks a route")
    void nullsNormalise() {
      Route route = new Route(true, null, null);
      assertEquals(List.of(), route.recipients());
      assertEquals("", route.subjectPrefix());
    }

    @Test
    @DisplayName("the recipient list is a defensive copy")
    void recipientsAreCopied() {
      List<String> mutable = new ArrayList<>(List.of("a@x.com"));
      Route route = new Route(true, mutable, "[x]");
      mutable.clear();
      assertEquals(1, route.recipients().size(), "a route does not change under its caller");
    }

    @Test
    @DisplayName("silent() is a route, not a null")
    void silentIsARoute() {
      // A null would put a null check in front of every shouldSend(), to describe a case the
      // type already expresses.
      Route silent = Route.silent();
      assertFalse(silent.shouldSend());
      assertFalse(silent.enabled());
      assertEquals(List.of(), silent.recipients());
      assertEquals("", silent.subjectPrefix());
    }
  }

  @Nested
  @DisplayName("the built-in policies")
  class Builtins {

    @Test
    @DisplayName("fixed() answers the same route for every scope")
    void fixedIgnoresScope() {
      AlertRoutingPolicy policy = AlertRoutingPolicy.fixed(List.of("ops@example.com"), "[all]");

      Route mark = policy.routeFor(Business.MARK, "CUSTODY");
      Route unknown = policy.routeFor(null, null);

      assertTrue(mark.shouldSend());
      assertTrue(unknown.shouldSend(),
          "an invoice whose business never resolved still has to reach someone");
      assertEquals(mark.recipients(), unknown.recipients());
      assertEquals("[all]", mark.subjectPrefix());
    }

    @Test
    @DisplayName("silent() answers no for every scope")
    void silentIgnoresScope() {
      AlertRoutingPolicy policy = AlertRoutingPolicy.silent();
      assertFalse(policy.routeFor(Business.MARK, "CUSTODY").shouldSend());
      assertFalse(policy.routeFor(null, null).shouldSend());
    }
  }
}
