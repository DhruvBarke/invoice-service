package com.sg.domain.cache;

import static org.junit.jupiter.api.Assertions.*;

import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.model.party.RegistrationType;
import com.sg.domaininterface.port.in.PartyRegistrationUnavailableException;
import com.sg.domaininterface.port.in.UnavailabilityReason;
import com.sg.domaininterface.port.out.GuardDecision;
import com.sg.domaininterface.port.out.ReferentialGateway;
import com.sg.domaininterface.port.out.ResponseGuard;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Exercises the cache with a lambda gateway — no referential, no database. That the cache is
 * testable this way is the reason {@code ReferentialGateway} is narrower than the referential's own
 * interface.
 */
class CacheBehaviourTest {

    private static PartyRegistrationDetails record(String golden, String siren, String siret) {
        return new PartyRegistrationDetails(golden, "Office", "OFF", "TP-1", "Co", "CO",
                golden, "Office", "OFF", siren, siret, List.of());
    }

    private static ReferentialGateway counting(AtomicInteger calls,
                                                List<PartyRegistrationDetails> result) {
        return new ReferentialGateway() {
            public List<PartyRegistrationDetails> searchByBdrId(String b) {
                calls.incrementAndGet();
                return result;
            }
            public List<PartyRegistrationDetails> searchByRegistration(String v, RegistrationType t) {
                calls.incrementAndGet();
                return result;
            }
        };
    }

    @Test
    void secondLookupIsServedFromCache() {
        var calls = new AtomicInteger();
        var gateway = counting(calls, List.of(record("G1", "123456789", "12345678900012")));

        try (var cache = new OutboundPartyRegistrationCache(
                gateway, CacheConfig.defaults(), ResponseGuard.passThrough())) {
            cache.findByBdrId("G1");
            cache.findByBdrId("G1");
            assertEquals(1, calls.get(), "the second lookup must not reach the referential");
            assertEquals(1, cache.stats().hits());
        }
    }

    /** Formatting differences must not create a second entry for the same party. */
    @Test
    void formattedAndCleanRegistrationNumbersShareOneEntry() {
        var calls = new AtomicInteger();
        var gateway = counting(calls, List.of(record("G1", "123456789", "12345678900012")));

        try (var cache = new InboundPartyRegistrationCache(
                gateway, CacheConfig.defaults(), ResponseGuard.passThrough())) {
            assertTrue(cache.findBySiren("123 456 789").isPresent());
            assertTrue(cache.findBySiren("123456789").isPresent());
            assertEquals(1, calls.get());
        }
    }

    /** An unknown id must not loop against the referential. */
    @Test
    void absenceIsCachedNegatively() {
        var calls = new AtomicInteger();
        try (var cache = new OutboundPartyRegistrationCache(
                counting(calls, List.of()), CacheConfig.defaults(), ResponseGuard.passThrough())) {
            assertTrue(cache.findByBdrId("G-404").isEmpty());
            assertTrue(cache.findByBdrId("G-404").isEmpty());
            assertEquals(1, calls.get());
        }
    }

    /** A transient outage must NOT be frozen in for the entry lifetime. */
    @Test
    void upstreamFailureIsNotCachedAndIsReportedAsRetryable() {
        var calls = new AtomicInteger();
        ReferentialGateway failing = new ReferentialGateway() {
            public List<PartyRegistrationDetails> searchByBdrId(String b) {
                calls.incrementAndGet();
                throw new ReferentialUnavailableException("endpoint down", null);
            }
            public List<PartyRegistrationDetails> searchByRegistration(String v, RegistrationType t) {
                throw new ReferentialUnavailableException("endpoint down", null);
            }
        };

        try (var cache = new OutboundPartyRegistrationCache(
                failing, CacheConfig.defaults(), ResponseGuard.passThrough())) {
            var e = assertThrows(PartyRegistrationUnavailableException.class,
                    () -> cache.findByBdrId("G1"));
            assertEquals(UnavailabilityReason.UPSTREAM_UNAVAILABLE, e.reason());
            assertTrue(e.isRetryable());

            assertThrows(PartyRegistrationUnavailableException.class, () -> cache.findByBdrId("G1"));
            assertEquals(2, calls.get(), "an outage must be retried, not cached");
        }
    }

    @Test
    void aBlockingGuardWithholdsTheRecordAndCachesTheBlock() {
        var calls = new AtomicInteger();
        var gateway = counting(calls, List.of(record("G1", null, null)));
        ResponseGuard blocking = (flow, keySpace, key, response) -> GuardDecision.block("row-42");

        try (var cache = new OutboundPartyRegistrationCache(gateway, CacheConfig.defaults(), blocking)) {
            var e = assertThrows(PartyRegistrationUnavailableException.class,
                    () -> cache.findByBdrId("G1"));
            assertEquals(UnavailabilityReason.BLOCKED, e.reason());
            assertEquals("row-42", e.referenceId(), "operators need a quotable handle");

            assertThrows(PartyRegistrationUnavailableException.class, () -> cache.findByBdrId("G1"));
            assertEquals(1, calls.get(), "the block is cached so a hot defect cannot hammer upstream");
        }
    }

    /** A guard failure must degrade data-quality tracking, never availability. */
    @Test
    void aThrowingGuardDoesNotBreakLookups() {
        var gateway = counting(new AtomicInteger(),
                List.of(record("G1", "123456789", "12345678900012")));
        ResponseGuard exploding = (flow, keySpace, key, response) -> {
            throw new IllegalStateException("quarantine database is down");
        };

        try (var cache = new OutboundPartyRegistrationCache(gateway, CacheConfig.defaults(), exploding)) {
            assertTrue(cache.findByBdrId("G1").isPresent());
        }
    }

    @Test
    void siretDuplicatesAreAllReturnedButCollapseDeterministicallyForFind() {
        var duplicates = List.of(
                new PartyRegistrationDetails("ELEM-9", "Lyon", "LY", "TP-1", "Co", "CO",
                        "G1", "Lyon", "LY", "123456789", "12345678900012", List.of()),
                record("G1", "123456789", "12345678900012"));

        try (var cache = new InboundPartyRegistrationCache(
                counting(new AtomicInteger(), duplicates), CacheConfig.defaults(),
                ResponseGuard.passThrough())) {
            assertEquals(2, cache.findAllBySiret("12345678900012").size());
            assertEquals("G1", cache.findBySiret("12345678900012").orElseThrow().goldenBdrId());
        }
    }

    @Test
    void malformedIdentifiersMissWithoutTouchingTheReferential() {
        var calls = new AtomicInteger();
        try (var cache = new InboundPartyRegistrationCache(
                counting(calls, List.of()), CacheConfig.defaults(), ResponseGuard.passThrough())) {
            assertTrue(cache.findBySiren("nonsense").isEmpty());
            assertTrue(cache.findBySiren(null).isEmpty());
            assertEquals(0, calls.get(), "fail fast, do not spend a round trip");
        }
    }
}
