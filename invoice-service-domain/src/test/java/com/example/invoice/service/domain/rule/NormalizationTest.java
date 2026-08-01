package com.example.invoice.service.domain.rule;

import static org.junit.jupiter.api.Assertions.*;

import com.example.invoice.service.domain.model.KeySpace;
import com.example.invoice.service.domain.model.RegistrationType;
import org.junit.jupiter.api.Test;

/**
 * Normalization is shared so the cache, the quarantine table and the mappers cannot disagree about
 * whether two spellings are the same party. These tests pin that contract.
 */
class NormalizationTest {

    @Test
    void formattedAndCleanSirensCollapseToOneKey() {
        assertEquals("123456789", RegistrationType.SIREN.normalize("123 456 789"));
        assertEquals("123456789", RegistrationType.SIREN.normalize("123.456.789"));
        assertEquals("123456789", RegistrationType.SIREN.normalize("123456789"));
    }

    /** The clean form is the hot path and must be returned as-is, with no allocation. */
    @Test
    void cleanInputReturnsTheSameInstance() {
        String clean = "12345678900012";
        assertSame(clean, RegistrationType.SIRET.normalize(clean));
    }

    @Test
    void wrongLengthsAreRejectedRatherThanSentUpstream() {
        assertNull(RegistrationType.SIREN.normalize("12345"));
        assertNull(RegistrationType.SIRET.normalize("123456789"));
        assertNull(RegistrationType.SIREN.normalize("nonsense"));
        assertNull(RegistrationType.SIREN.normalize(null));
    }

    /** Guards against the Turkish dotted-I mapping that a default-locale toUpperCase would apply. */
    @Test
    void bdrIdsAreUppercasedLocaleIndependently() {
        assertEquals("BDR-G-001", KeySpace.BDR_ID.normalize("  bdr-g-001 "));
        assertNull(KeySpace.BDR_ID.normalize("   "));
    }

    @Test
    void keySpaceCardinalityMatchesReality() {
        assertTrue(KeySpace.SIRET.isMultiValued(), "duplicate offices share a SIRET");
        assertFalse(KeySpace.SIREN.isMultiValued());
        assertFalse(KeySpace.BDR_ID.isMultiValued());
    }
}
