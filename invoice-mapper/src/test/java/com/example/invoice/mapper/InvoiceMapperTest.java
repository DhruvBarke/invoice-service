package com.example.invoice.mapper;

import static org.junit.jupiter.api.Assertions.*;

import com.example.invoice.service.domain.model.PartyRegistrationDetails;
import com.example.invoice.service.domain.port.in.PartyRegistrationLookup;
import com.example.invoice.service.domain.port.in.PartyRegistrationUnavailableException;
import com.example.invoice.service.domain.port.in.UnavailabilityReason;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The whole architecture exists so that this test file needs no referential, no database, no mail
 * server and no Spring context. If a future change breaks that, this class stops compiling — which
 * is the point.
 */
class InvoiceMapperTest {

    private static final PartyRegistrationDetails DUPLICATE_OFFICE = new PartyRegistrationDetails(
            "ELEM-9", "Lyon branch", "LYON", "TP-1", "Acme SA", "ACME",
            "BDR-G-001", "Acme SA", "ACME", "123456789", "12345678900012", List.of());

    /** The four-line stub the package documentation promises. */
    private static PartyRegistrationLookup stub(PartyRegistrationDetails result) {
        return new PartyRegistrationLookup() {
            public Optional<PartyRegistrationDetails> findByBdrId(String b) { return Optional.ofNullable(result); }
            public Optional<PartyRegistrationDetails> findBySiren(String s) { return Optional.ofNullable(result); }
            public Optional<PartyRegistrationDetails> findBySiret(String s) { return Optional.ofNullable(result); }
            public List<PartyRegistrationDetails> findAllBySiret(String s) {
                return result == null ? List.of() : List.of(result);
            }
        };
    }

    /**
     * The rule most likely to be got wrong by a future contributor: registration keys on the GOLDEN
     * id even when the resolved record is a duplicate carrying a different elementary id.
     */
    @Test
    void mappersAlwaysRegisterAgainstTheGoldenId() {
        var inbound = new InvoiceInboundFacadeMapper(stub(DUPLICATE_OFFICE));
        var outbound = new InvoiceOutboundFacadeMapper(stub(DUPLICATE_OFFICE));

        assertEquals("BDR-G-001", inbound.mapSupplierBySiren("123456789").registrationId());
        assertEquals("BDR-G-001", outbound.mapCounterparty("ELEM-9").registrationId());
    }

    @Test
    void anUnknownPartyRaisesNotFoundWhichIsNotRetryable() {
        var mapper = new InvoiceInboundFacadeMapper(stub(null));
        var e = assertThrows(PartyRegistrationUnavailableException.class,
                () -> mapper.mapSupplierBySiren("123456789"));
        assertEquals(UnavailabilityReason.NOT_FOUND, e.reason());
        assertFalse(e.isRetryable());
    }

    /** Reports want every duplicate, not the collapsed golden record. */
    @Test
    void reportMapperExposesAllEstablishments() {
        var report = new ReportFacadeMapper(stub(DUPLICATE_OFFICE));
        assertEquals(1, report.allEstablishments("12345678900012").size());
        assertEquals("Acme SA", report.displayName("BDR-G-001").orElseThrow());
    }

    /** Reports tolerate absence: a blank cell, not a failure. */
    @Test
    void reportMapperTreatsAbsenceAsEmpty() {
        var report = new ReportFacadeMapper(stub(null));
        assertTrue(report.displayName("BDR-G-404").isEmpty());
        assertTrue(report.allEstablishments("12345678900012").isEmpty());
    }
}
