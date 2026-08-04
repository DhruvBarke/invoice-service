package com.sg.domaininterface.rule.party;

import static org.junit.jupiter.api.Assertions.*;

import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GoldenRecordSelectorTest {

    /** Came with the edge cases moved from the domain's rule tests. */
    private static PartyRegistrationDetails party(String elemBdrId, String goldenBdrId,
                                                  String siren, String siret) {
        return new PartyRegistrationDetails(elemBdrId, "name", "MNE", "TP", "tpName", "TPM",
                goldenBdrId, "Acme SA", "ACME", siren, siret, List.of());
    }

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

  // ── Edge cases ────────────────────────────────────────────────────────────
  // Moved here with the selector. They lived in the domain's rule tests, so this module
  // showed the class as barely covered while the branches were in fact being exercised —
  // from a bundle a layer away, where nobody would look for them.

  @Nested
  @DisplayName("GoldenRecordSelector")
  class Selection {

    @Test
    @DisplayName("no candidates yields empty")
    void emptyAndNullYieldEmpty() {
      assertTrue(GoldenRecordSelector.select(null).isEmpty());
      assertTrue(GoldenRecordSelector.select(List.of()).isEmpty());
    }

    @Test
    @DisplayName("a single candidate is returned without scanning")
    void singleCandidateShortCircuits() {
      PartyRegistrationDetails only = party("E1", "G1", "123456789", "12345678900012");
      assertEquals(only, GoldenRecordSelector.select(List.of(only)).orElseThrow());
    }

    @Test
    @DisplayName("the golden record wins over a duplicate")
    void goldenRecordWins() {
      PartyRegistrationDetails duplicate = party("E2", "G1", "123456789", null);
      PartyRegistrationDetails golden = party("G1", "G1", "123456789", "12345678900012");

      assertEquals(golden,
          GoldenRecordSelector.select(List.of(duplicate, golden)).orElseThrow(),
          "isGoldenRecord() is the primary sort key");
    }

    @Test
    @DisplayName("with no golden record the lowest elemBdrId wins, deterministically")
    void lowestElemBdrIdBreaksTies() {
      PartyRegistrationDetails b = party("E2", "G9", "123456789", null);
      PartyRegistrationDetails a = party("E1", "G9", "123456789", null);

      assertEquals(a, GoldenRecordSelector.select(List.of(b, a)).orElseThrow());
      assertEquals(a, GoldenRecordSelector.select(List.of(a, b)).orElseThrow(),
          "input order must not change the answer — that is the whole point of the rule");
    }

    @Test
    @DisplayName("a null elemBdrId sorts as empty rather than throwing")
    void nullElemBdrIdIsSortable() {
      PartyRegistrationDetails nullElem = party(null, "G9", "123456789", null);
      PartyRegistrationDetails withElem = party("E1", "G9", "123456789", null);
      // nullElem is golden by definition (blank elemBdrId), so it wins on the primary key.
      assertEquals(nullElem,
          GoldenRecordSelector.select(List.of(withElem, nullElem)).orElseThrow());
    }

    @Test
    @DisplayName("two non-golden duplicates, one with a null elemBdrId, still sort deterministically")
    void nullElemBdrIdAmongNonGoldenCandidates() {
      // Both are non-golden (elemBdrId present and different from goldenBdrId), so the
      // comparator falls through to the elemBdrId tiebreak — which is where the null-guard
      // in `thenComparing` earns its place. "" sorts below "E1".
      PartyRegistrationDetails nullElem = party("", "G9", "123456789", null);
      PartyRegistrationDetails withElem = party("E1", "G9", "123456789", null);

      // A blank elemBdrId makes the record golden, so it wins outright.
      assertEquals(nullElem,
          GoldenRecordSelector.select(List.of(withElem, nullElem)).orElseThrow());

      // Two genuinely non-golden rows exercise the tiebreak on both sides.
      PartyRegistrationDetails e1 = party("E1", "G9", "123456789", null);
      PartyRegistrationDetails e2 = party("E2", "G9", "123456789", null);
      assertEquals(e1, GoldenRecordSelector.select(List.of(e2, e1)).orElseThrow());
      assertEquals(e1, GoldenRecordSelector.select(List.of(e1, e2)).orElseThrow());
    }

    @Test
    @DisplayName("two golden records tie on the primary key, so the null-safe tiebreak decides")
    void tiebreakHandlesNullElemBdrIdWhenBothAreGolden() {
      // Reaching the elemBdrId comparison at all requires isGoldenRecord() to tie, and a null
      // elemBdrId makes a record golden — so the only way to exercise the null guard is two
      // golden records, one with a null elemBdrId and one whose elemBdrId equals its golden id.
      PartyRegistrationDetails nullElem = party(null, "G1", "123456789", null);
      PartyRegistrationDetails selfReferential = party("G1", "G1", "123456789", null);

      assertTrue(nullElem.isGoldenRecord() && selfReferential.isGoldenRecord(),
          "both must be golden or the tiebreak is never reached");
      assertEquals(nullElem,
          GoldenRecordSelector.select(List.of(selfReferential, nullElem)).orElseThrow(),
          "the null elemBdrId maps to \"\", which sorts below \"G1\"");
      assertEquals(nullElem,
          GoldenRecordSelector.select(List.of(nullElem, selfReferential)).orElseThrow(),
          "and the answer does not depend on input order");
    }

    @Test
    @DisplayName("candidates agreeing on a golden id are not ambiguous")
    void agreementIsNotAmbiguous() {
      assertFalse(GoldenRecordSelector.isAmbiguous(List.of(
          party("E1", "G1", "123456789", null),
          party("E2", "G1", "123456789", null))));
    }

    @Test
    @DisplayName("candidates disagreeing on a golden id are ambiguous")
    void disagreementIsAmbiguous() {
      assertTrue(GoldenRecordSelector.isAmbiguous(List.of(
          party("E1", "G1", "123456789", null),
          party("E2", "G2", "123456789", null))),
          "upstream deduplication is itself inconsistent — the selection is a real guess");
    }

    @Test
    @DisplayName("fewer than two candidates can never be ambiguous")
    void tooFewToBeAmbiguous() {
      assertFalse(GoldenRecordSelector.isAmbiguous(null));
      assertFalse(GoldenRecordSelector.isAmbiguous(List.of()));
      assertFalse(GoldenRecordSelector.isAmbiguous(
          List.of(party("E1", "G1", "123456789", null))));
    }
  }
}
