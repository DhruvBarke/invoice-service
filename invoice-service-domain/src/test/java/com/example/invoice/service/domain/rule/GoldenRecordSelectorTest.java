package com.example.invoice.service.domain.rule;

import static org.junit.jupiter.api.Assertions.*;

import com.example.invoice.service.domain.model.PartyRegistrationDetails;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoldenRecordSelectorTest {

    private static PartyRegistrationDetails record(String golden, String elem) {
        return new PartyRegistrationDetails(elem, "Office", "OFF", "TP-1", "Co", "CO",
                golden, "Office", "OFF", "123456789", "12345678900012", List.of());
    }

    @Test
    void prefersTheGoldenRecord() {
        var duplicate = record("G1", "ELEM-9");
        var master = record("G1", "G1");
        assertEquals(master, GoldenRecordSelector.select(List.of(duplicate, master)).orElseThrow());
    }

    /**
     * The property that matters most: an invoice registered against one row and reconciled against
     * another is far worse than picking the less appropriate of two.
     */
    @Test
    void selectionIsStableAcrossCallsAndInputOrder() {
        var a = record("G1", "ELEM-9");
        var b = record("G2", "ELEM-2");
        var first = GoldenRecordSelector.select(List.of(a, b)).orElseThrow();
        var reversed = GoldenRecordSelector.select(List.of(b, a)).orElseThrow();
        assertEquals(first, reversed);
        assertEquals(first, GoldenRecordSelector.select(List.of(a, b)).orElseThrow());
    }

    @Test
    void detectsDisagreementOnGoldenId() {
        assertTrue(GoldenRecordSelector.isAmbiguous(List.of(record("G1", "E1"), record("G2", "E2"))));
        assertFalse(GoldenRecordSelector.isAmbiguous(List.of(record("G1", "E1"), record("G1", "E2"))));
    }
}
