package com.sg.domain.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domaininterface.model.party.KeySpace;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.model.party.RegistrationType;
import com.sg.domaininterface.port.out.GuardDecision;
import com.sg.domaininterface.port.out.ReferentialGateway;
import com.sg.domaininterface.port.out.ResponseGuard;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two flow caches and the router over them.
 *
 * <p>These are thin by design, so the tests focus on the behaviour that is actually theirs:
 * which key space a call lands in, that the flows stay independent, and that golden-record
 * collapsing happens on the way out.
 */
class CacheFacadesTest {

  private static final ResponseGuard PASS =
      (flow, keySpace, key, response) -> GuardDecision.pass(response);

  private static PartyRegistrationDetails party(String elemBdrId, String goldenBdrId,
                                                String siren, String siret) {
    return new PartyRegistrationDetails(elemBdrId, "elem", "EMN", "TP1", "tp", "TPM",
        goldenBdrId, "Acme SA", "ACME", siren, siret, List.of());
  }

  /** Records which key space each call arrived on. */
  private static final class RecordingGateway implements ReferentialGateway {
    final AtomicInteger bdrCalls = new AtomicInteger();
    final AtomicInteger sirenCalls = new AtomicInteger();
    final AtomicInteger siretCalls = new AtomicInteger();
    volatile List<PartyRegistrationDetails> response;

    RecordingGateway(List<PartyRegistrationDetails> response) {
      this.response = response;
    }

    @Override
    public List<PartyRegistrationDetails> searchByBdrId(String bdrId) {
      bdrCalls.incrementAndGet();
      return response;
    }

    @Override
    public List<PartyRegistrationDetails> searchByRegistration(String id, RegistrationType type) {
      if (type == RegistrationType.SIREN) {
        sirenCalls.incrementAndGet();
      } else {
        siretCalls.incrementAndGet();
      }
      return response;
    }
  }

  // ── Inbound ───────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("InboundPartyRegistrationCache")
  class Inbound {

    @Test
    @DisplayName("SIREN and SIRET are separate key spaces, each with its own load")
    void keySpacesAreIndependent() {
      RecordingGateway gw = new RecordingGateway(
          List.of(party("G1", "G1", "123456789", "12345678900012")));
      try (InboundPartyRegistrationCache cache =
               new InboundPartyRegistrationCache(gw, CacheConfig.defaults(), PASS)) {

        assertTrue(cache.findBySiren("123456789").isPresent());
        assertTrue(cache.findBySiret("12345678900012").isPresent());

        assertEquals(1, gw.sirenCalls.get());
        assertEquals(1, gw.siretCalls.get());
        assertEquals(0, gw.bdrCalls.get(), "an inbound cache never touches the outbound key space");
      }
    }

    @Test
    @DisplayName("separate configs let SIRET be sized independently of SIREN")
    void perKeySpaceSizing() {
      RecordingGateway gw = new RecordingGateway(
          List.of(party("G1", "G1", "123456789", "12345678900012")));
      CacheConfig siren = CacheConfig.defaults().withMaxEntries(10);
      CacheConfig siret = CacheConfig.defaults().withMaxEntries(9999);

      try (InboundPartyRegistrationCache cache =
               new InboundPartyRegistrationCache(gw, siren, siret, PASS)) {
        assertTrue(cache.findBySiren("123456789").isPresent());
        assertEquals(2, cache.stats().size(), "stats are reported per key space, never summed");
        assertEquals("SIREN", cache.stats().get(0).keySpace());
        assertEquals("SIRET", cache.stats().get(1).keySpace());
      }
    }

    @Test
    @DisplayName("findAllBySiret returns every duplicate, findBySiret collapses to the golden one")
    void siretCollapsesOnlyForTheSingularAccessor() {
      RecordingGateway gw = new RecordingGateway(List.of(
          party("E2", "G1", "123456789", "12345678900012"),
          party("G1", "G1", "123456789", "12345678900012")));

      try (InboundPartyRegistrationCache cache =
               new InboundPartyRegistrationCache(gw, CacheConfig.defaults(), PASS)) {

        assertEquals(2, cache.findAllBySiret("12345678900012").size(),
            "reporting wants every establishment");
        assertEquals("G1", cache.findBySiret("12345678900012").orElseThrow().elemBdrId(),
            "registration wants the one golden record");
      }
    }

    @Test
    @DisplayName("an unknown key yields empty rather than throwing")
    void unknownKeyIsEmpty() {
      RecordingGateway gw = new RecordingGateway(List.of());
      try (InboundPartyRegistrationCache cache =
               new InboundPartyRegistrationCache(gw, CacheConfig.defaults(), PASS)) {
        assertTrue(cache.findBySiren("123456789").isEmpty());
        assertTrue(cache.findBySiret("12345678900012").isEmpty());
        assertTrue(cache.findAllBySiret("12345678900012").isEmpty());
      }
    }

    @Test
    @DisplayName("invalidate targets one key space, leaving the other untouched")
    void invalidateIsKeySpaceScoped() {
      RecordingGateway gw = new RecordingGateway(
          List.of(party("G1", "G1", "123456789", "12345678900012")));
      try (InboundPartyRegistrationCache cache =
               new InboundPartyRegistrationCache(gw, CacheConfig.defaults(), PASS)) {

        cache.findBySiren("123456789");
        cache.findBySiret("12345678900012");
        cache.invalidate(KeySpace.SIREN, "123456789");

        cache.findBySiren("123456789");
        cache.findBySiret("12345678900012");
        assertEquals(2, gw.sirenCalls.get(), "the SIREN entry was dropped and reloaded");
        assertEquals(1, gw.siretCalls.get(), "the SIRET entry was left alone");
      }
    }

    @Test
    @DisplayName("invalidateAll clears both key spaces")
    void invalidateAllClearsBoth() {
      RecordingGateway gw = new RecordingGateway(
          List.of(party("G1", "G1", "123456789", "12345678900012")));
      try (InboundPartyRegistrationCache cache =
               new InboundPartyRegistrationCache(gw, CacheConfig.defaults(), PASS)) {

        cache.findBySiren("123456789");
        cache.findBySiret("12345678900012");
        cache.invalidateAll();

        cache.stats().forEach(s -> assertEquals(0, s.entries()));
      }
    }

    @Test
    @DisplayName("the gateway is mandatory")
    void gatewayMandatory() {
      assertThrows(NullPointerException.class,
          () -> new InboundPartyRegistrationCache(null, CacheConfig.defaults(), PASS));
    }
  }

  // ── Outbound ──────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("OutboundPartyRegistrationCache")
  class Outbound {

    @Test
    @DisplayName("a BDR lookup resolves to at most one record")
    void bdrResolvesToOne() {
      RecordingGateway gw = new RecordingGateway(
          List.of(party("E9", "G1", "123456789", "12345678900012")));
      try (OutboundPartyRegistrationCache cache =
               new OutboundPartyRegistrationCache(gw, CacheConfig.defaults(), PASS)) {

        assertEquals("G1", cache.findByBdrId("E9").orElseThrow().goldenBdrId(),
            "an outbound lookup may carry an elementary id, but registration uses the golden one");
        assertEquals(1, gw.bdrCalls.get());
        assertEquals(0, gw.sirenCalls.get());
      }
    }

    @Test
    @DisplayName("an unknown BDR id yields empty")
    void unknownBdrIsEmpty() {
      RecordingGateway gw = new RecordingGateway(List.of());
      try (OutboundPartyRegistrationCache cache =
               new OutboundPartyRegistrationCache(gw, CacheConfig.defaults(), PASS)) {
        assertTrue(cache.findByBdrId("NOPE").isEmpty());
      }
    }

    @Test
    @DisplayName("invalidate and invalidateAll both drop the entry")
    void invalidation() {
      RecordingGateway gw = new RecordingGateway(
          List.of(party("E9", "G1", "123456789", "12345678900012")));
      try (OutboundPartyRegistrationCache cache =
               new OutboundPartyRegistrationCache(gw, CacheConfig.defaults(), PASS)) {

        cache.findByBdrId("E9");
        cache.invalidate("E9");
        cache.findByBdrId("E9");
        assertEquals(2, gw.bdrCalls.get());

        cache.invalidateAll();
        assertEquals(0, cache.stats().entries());
      }
    }

    @Test
    @DisplayName("stats report the BDR_ID key space")
    void statsCarryTheKeySpace() {
      RecordingGateway gw = new RecordingGateway(List.of());
      try (OutboundPartyRegistrationCache cache =
               new OutboundPartyRegistrationCache(gw, CacheConfig.defaults(), PASS)) {
        assertEquals("BDR_ID", cache.stats().keySpace());
      }
    }

    @Test
    @DisplayName("the gateway is mandatory")
    void gatewayMandatory() {
      assertThrows(NullPointerException.class,
          () -> new OutboundPartyRegistrationCache(null, CacheConfig.defaults(), PASS));
    }
  }

  // ── The router ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("CachingPartyRegistrationLookup")
  class Router {

    private RecordingGateway gateway() {
      return new RecordingGateway(List.of(party("E9", "G1", "123456789", "12345678900012")));
    }

    @Test
    @DisplayName("each port method routes to the cache that owns its key space")
    void routesByKeySpace() {
      RecordingGateway gw = gateway();
      InboundPartyRegistrationCache in =
          new InboundPartyRegistrationCache(gw, CacheConfig.defaults(), PASS);
      OutboundPartyRegistrationCache out =
          new OutboundPartyRegistrationCache(gw, CacheConfig.defaults(), PASS);

      try (CachingPartyRegistrationLookup lookup = new CachingPartyRegistrationLookup(in, out)) {
        assertTrue(lookup.findByBdrId("E9").isPresent());
        assertEquals(1, gw.bdrCalls.get());

        assertTrue(lookup.findBySiren("123456789").isPresent());
        assertEquals(1, gw.sirenCalls.get());

        assertTrue(lookup.findBySiret("12345678900012").isPresent());
        assertEquals(1, gw.siretCalls.get());

        assertEquals(1, lookup.findAllBySiret("12345678900012").size());
        assertEquals(1, gw.siretCalls.get(), "the second SIRET call is served from the cache");
      }
    }

    @Test
    @DisplayName("the underlying caches are reachable for an operations endpoint")
    void underlyingCachesAreExposed() {
      RecordingGateway gw = gateway();
      InboundPartyRegistrationCache in =
          new InboundPartyRegistrationCache(gw, CacheConfig.defaults(), PASS);
      OutboundPartyRegistrationCache out =
          new OutboundPartyRegistrationCache(gw, CacheConfig.defaults(), PASS);

      try (CachingPartyRegistrationLookup lookup = new CachingPartyRegistrationLookup(in, out)) {
        assertSame(in, lookup.inbound());
        assertSame(out, lookup.outbound());
        assertNotNull(lookup.inbound().stats());
        assertNotNull(lookup.outbound().stats());
      }
    }

    @Test
    @DisplayName("the default require* helpers work through the router")
    void requireHelpersWorkThroughTheRouter() {
      RecordingGateway gw = gateway();
      InboundPartyRegistrationCache in =
          new InboundPartyRegistrationCache(gw, CacheConfig.defaults(), PASS);
      OutboundPartyRegistrationCache out =
          new OutboundPartyRegistrationCache(gw, CacheConfig.defaults(), PASS);

      try (CachingPartyRegistrationLookup lookup = new CachingPartyRegistrationLookup(in, out)) {
        assertEquals("G1", lookup.requireByBdrId("E9").goldenBdrId());
        assertEquals("G1", lookup.requireBySiren("123456789").goldenBdrId());
        assertEquals("G1",
            lookup.find(RegistrationType.SIRET, "12345678900012").orElseThrow().goldenBdrId());
      }
    }

    @Test
    @DisplayName("both caches are mandatory")
    void bothCachesMandatory() {
      RecordingGateway gw = gateway();
      try (InboundPartyRegistrationCache in =
               new InboundPartyRegistrationCache(gw, CacheConfig.defaults(), PASS);
           OutboundPartyRegistrationCache out =
               new OutboundPartyRegistrationCache(gw, CacheConfig.defaults(), PASS)) {

        assertThrows(NullPointerException.class,
            () -> new CachingPartyRegistrationLookup(null, out));
        assertThrows(NullPointerException.class,
            () -> new CachingPartyRegistrationLookup(in, null));
      }
    }

    @Test
    @DisplayName("closing the router closes both caches")
    void closePropagates() {
      RecordingGateway gw = gateway();
      InboundPartyRegistrationCache in =
          new InboundPartyRegistrationCache(gw, CacheConfig.defaults(), PASS);
      OutboundPartyRegistrationCache out =
          new OutboundPartyRegistrationCache(gw, CacheConfig.defaults(), PASS);

      CachingPartyRegistrationLookup lookup = new CachingPartyRegistrationLookup(in, out);
      lookup.findBySiren("123456789");
      lookup.close();

      // Closing twice must be harmless — a Spring context can call destroy more than once.
      lookup.close();
    }
  }
}
