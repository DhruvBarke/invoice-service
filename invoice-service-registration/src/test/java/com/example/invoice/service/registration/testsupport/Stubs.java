package com.example.invoice.service.registration.testsupport;

import com.example.invoice.mapper.einvoice.FeeTypeMatcher;
import com.example.invoice.service.domain.model.PartyRegistrationDetails;
import com.example.invoice.service.domain.port.in.PartyRegistrationLookup;
import com.example.invoice.service.registration.port.InvoicePayableStore;
import com.example.invoice.service.registration.port.LifecycleEventPublisher;
import com.example.invoice.service.registration.port.RegistrationAlertNotifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory doubles for every port the registration pipeline depends on.
 *
 * <p>That these are four small classes with no framework behind them is the payoff of the
 * module's enforcer rule. If a future change makes the orchestrator need a Spring context or a
 * DataSource, this file stops compiling — which is the intended alarm.
 */
public final class Stubs {

  private Stubs() {}

  public static final PartyRegistrationDetails ACME = new PartyRegistrationDetails(
      "ELEM-9", "Lyon branch", "LYON", "TP-1", "Acme SA", "ACME",
      "BDR-G-001", "Acme SA", "ACME", "123456789", "12345678900012", List.of());

  /** Always resolves. */
  public static PartyRegistrationLookup lookup() {
    return lookup(ACME);
  }

  /** Resolves to {@code result}, or returns empty when {@code result} is null. */
  public static PartyRegistrationLookup lookup(PartyRegistrationDetails result) {
    return new PartyRegistrationLookup() {
      @Override public Optional<PartyRegistrationDetails> findByBdrId(String b) {
        return Optional.ofNullable(result);
      }
      @Override public Optional<PartyRegistrationDetails> findBySiren(String s) {
        return Optional.ofNullable(result);
      }
      @Override public Optional<PartyRegistrationDetails> findBySiret(String s) {
        return Optional.ofNullable(result);
      }
      @Override public List<PartyRegistrationDetails> findAllBySiret(String s) {
        return result == null ? List.of() : List.of(result);
      }
    };
  }

  /** Throws on every lookup — drives the PARTY_LOOKUP_FAILED path. */
  public static PartyRegistrationLookup throwingLookup(RuntimeException toThrow) {
    return new PartyRegistrationLookup() {
      @Override public Optional<PartyRegistrationDetails> findByBdrId(String b) { throw toThrow; }
      @Override public Optional<PartyRegistrationDetails> findBySiren(String s) { throw toThrow; }
      @Override public Optional<PartyRegistrationDetails> findBySiret(String s) { throw toThrow; }
      @Override public List<PartyRegistrationDetails> findAllBySiret(String s) { throw toThrow; }
    };
  }

  /** The fee-type referential the fixtures are written against. */
  public static Map<String, String> referential() {
    return Map.of(
        "F01", "CUSTODY",
        "F02", "EXCHANGE",
        "F03", "CLEARING",
        "F04", "BROKERAGE_PRINCIPAL",
        "F05", "BROKERAGE_AGENCY");
  }

  public static FeeTypeMatcher matcher() {
    Map<String, String> ref = referential();
    return new FeeTypeMatcher(() -> ref);
  }

  /** Records the last persist request and hands back a fixed row id. */
  public static final class RecordingStore implements InvoicePayableStore {
    public static final long ROW_ID = 42L;
    public final AtomicReference<PersistRequest> last = new AtomicReference<>();
    public int calls;

    @Override public long persist(PersistRequest request) {
      last.set(request);
      calls++;
      return ROW_ID;
    }
  }

  /** Collects queued lifecycle events. */
  public static final class RecordingPublisher implements LifecycleEventPublisher {
    public final List<PendingLifecycleEvent> events = new ArrayList<>();
    @Override public void publish(PendingLifecycleEvent e) { events.add(e); }
  }

  /** Fails on publish — drives the "publisher blew up" branch. */
  public static final class ThrowingPublisher implements LifecycleEventPublisher {
    @Override public void publish(PendingLifecycleEvent e) {
      throw new IllegalStateException("lifecycle store is down");
    }
  }

  /** Collects alerts. */
  public static final class RecordingNotifier implements RegistrationAlertNotifier {
    public final List<RegistrationAlert> alerts = new ArrayList<>();
    @Override public void notify(RegistrationAlert a) { alerts.add(a); }
  }

  /** Fails on notify — proves alerting can never fail a registration. */
  public static final class ThrowingNotifier implements RegistrationAlertNotifier {
    @Override public void notify(RegistrationAlert a) {
      throw new IllegalStateException("SMTP is down");
    }
  }
}
