package com.example.invoice.mapper.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.invoice.mapper.einvoice.FeeTypeMatcher.FeeTypeMatch;
import com.example.invoice.mapper.einvoice.FeeTypeMatcher.FeeTypeResolutionException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Token-set matching, the index fast path, ambiguity refusal, and index reuse.
 *
 * <p>The referential used throughout mirrors production: five fee types, two of which share the
 * {@code BROKERAGE} token — which is what makes the ambiguity cases real rather than contrived.
 */
class FeeTypeMatcherTest {

  private static final Map<String, String> REFERENTIAL = Map.of(
      "F01", "CUSTODY",
      "F02", "EXCHANGE",
      "F03", "CLEARING",
      "F04", "BROKERAGE_PRINCIPAL",
      "F05", "BROKERAGE_AGENCY");

  private static FeeTypeMatcher matcher() {
    return new FeeTypeMatcher(() -> REFERENTIAL);
  }

  // ── Matching ──────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("resolution")
  class Resolution {

    @ParameterizedTest(name = "[{0}] resolves to {1}")
    @CsvSource({
        "CUSTODY,        F01",
        "custody,        F01",
        "EXCHANGE,       F02",
        "CLEARING,       F03",
        "BROKERAGE_PRINCIPAL, F04",
        "BROKERAGE-PRINCIPAL, F04",
        "BROKERAGE PRINCIPAL, F04",
        "PRINCIPAL_BROKERAGE, F04",
        "BROKERAGE_AGENCY,    F05",
    })
    @DisplayName("separator, ordering and case variants all converge")
    void variantsConverge(String raw, String expectedFeeId) {
      assertEquals(expectedFeeId, matcher().resolve(raw).feeId());
    }

    @Test
    @DisplayName("a partial token resolves when only one entry can match it")
    void unambiguousPartialResolves() {
      assertEquals("F04", matcher().resolve("PRINCIPAL").feeId(),
          "BROKERAGE_AGENCY shares no token with PRINCIPAL, so there is no contest");
      assertEquals("F05", matcher().resolve("AGENCY").feeId());
    }

    @Test
    @DisplayName("the match carries both halves of the referential entry")
    void matchCarriesBothFields() {
      FeeTypeMatch m = matcher().resolve("CUSTODY");
      assertEquals("F01", m.feeId());
      assertEquals("CUSTODY", m.feeType());
    }

    @Test
    @DisplayName("convenience accessors return the two halves individually")
    void convenienceAccessors() {
      FeeTypeMatcher m = matcher();
      assertEquals("F01", m.toFeeId("CUSTODY"));
      assertEquals("CUSTODY", m.toFeeType("CUSTODY"));
      assertNull(m.toFeeId("NOSUCHTHING"));
      assertNull(m.toFeeType("NOSUCHTHING"));
    }
  }

  // ── Ambiguity and failure ─────────────────────────────────────────────────

  @Nested
  @DisplayName("refusal rather than guessing")
  class Refusal {

    @Test
    @DisplayName("a bare BROKERAGE ties against two entries and is refused")
    void ambiguousTokenIsRefused() {
      FeeTypeMatcher m = matcher();
      assertNull(m.resolveOrNull("BROKERAGE"),
          "guessing here would route half the invoices to the wrong fee id");

      String why = m.explainFailure("BROKERAGE");
      assertNotNull(why);
      assertTrue(why.contains("Ambiguous"), why);
      assertTrue(why.contains("2"), "the reason should say how many entries tied: " + why);
    }

    @Test
    @DisplayName("resolve throws where resolveOrNull returns null")
    void resolveThrowsOnAmbiguity() {
      FeeTypeResolutionException e = assertThrows(FeeTypeResolutionException.class,
          () -> matcher().resolve("BROKERAGE"));
      assertTrue(e.getMessage().contains("Ambiguous"));
    }

    @Test
    @DisplayName("a token matching nothing is reported as no match")
    void noMatchIsReported() {
      FeeTypeMatcher m = matcher();
      assertNull(m.resolveOrNull("NOSUCHTHING"));
      assertTrue(m.explainFailure("NOSUCHTHING").contains("No fee type match"));
      assertThrows(FeeTypeResolutionException.class, () -> m.resolve("NOSUCHTHING"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("null or blank input yields null and a stated reason")
    void blankInput(String raw) {
      FeeTypeMatcher m = matcher();
      assertNull(m.resolveOrNull(raw));
      assertEquals("Fee type is null/blank", m.explainFailure(raw));
    }

    @Test
    @DisplayName("a token that tokenises to nothing is reported")
    void separatorsOnlyTokeniseToNothing() {
      FeeTypeMatcher m = matcher();
      assertNull(m.resolveOrNull("___"));
      assertTrue(m.explainFailure("___").contains("No usable tokens"));
    }

    @Test
    @DisplayName("two referential entries with identical token sets are flagged, not resolved")
    void duplicateTokenSetsAreAmbiguous() {
      // CUSTODY_FEE and FEE_CUSTODY tokenise identically, so the referential itself is
      // ambiguous — the matcher must say so rather than pick whichever hashed first.
      Map<String, String> dupes = new LinkedHashMap<>();
      dupes.put("F10", "CUSTODY_FEE");
      dupes.put("F11", "FEE_CUSTODY");
      FeeTypeMatcher m = new FeeTypeMatcher(() -> dupes);

      assertNull(m.resolveOrNull("CUSTODY_FEE"));
      assertTrue(m.explainFailure("CUSTODY_FEE").contains("identical tokens"));
    }

    @Test
    @DisplayName("a null referential is a configuration fault, raised as one")
    void nullReferentialThrows() {
      FeeTypeMatcher m = new FeeTypeMatcher(() -> null);
      FeeTypeResolutionException e =
          assertThrows(FeeTypeResolutionException.class, () -> m.resolveOrNull("CUSTODY"));
      assertTrue(e.getMessage().contains("null map"));
    }

    @Test
    @DisplayName("an empty referential matches nothing but does not throw")
    void emptyReferentialMatchesNothing() {
      assertNull(new FeeTypeMatcher(Map::of).resolveOrNull("CUSTODY"));
    }

    @Test
    @DisplayName("a referential entry with a null value is skipped safely")
    void nullReferentialValueIsSkipped() {
      Map<String, String> withNull = new HashMap<>();
      withNull.put("F01", "CUSTODY");
      withNull.put("F99", null);
      FeeTypeMatcher m = new FeeTypeMatcher(() -> withNull);
      assertEquals("F01", m.resolve("CUSTODY").feeId());
    }

    @Test
    @DisplayName("the provider is mandatory")
    void providerMandatory() {
      assertThrows(NullPointerException.class, () -> new FeeTypeMatcher(null));
    }
  }

  // ── Legacy marker form ────────────────────────────────────────────────────

  @Nested
  @DisplayName("legacy composed-marker entry point")
  class LegacyMarker {

    @Test
    @DisplayName("the fee-type tail is taken from after _MARK_, underscores intact")
    void extractsAfterMark() {
      assertEquals("BROKERAGE_PRINCIPAL",
          FeeTypeMatcher.extractFeeType("552120222_MARK_BROKERAGE_PRINCIPAL"));
      assertEquals("CUSTODY", FeeTypeMatcher.extractFeeType("552120222_MARK_CUSTODY"));
    }

    @Test
    @DisplayName("without the marker it falls back to everything after the second underscore")
    void fallsBackToSecondUnderscore() {
      assertEquals("CUSTODY", FeeTypeMatcher.extractFeeType("552120222_SGSS_CUSTODY"));
    }

    @Test
    @DisplayName("a malformed code is rejected")
    void malformedCodeRejected() {
      assertThrows(IllegalArgumentException.class,
          () -> FeeTypeMatcher.extractFeeType("552120222_MARK_"));
      assertThrows(IllegalArgumentException.class,
          () -> FeeTypeMatcher.extractFeeType("552120222"));
      assertThrows(IllegalArgumentException.class,
          () -> FeeTypeMatcher.extractFeeType("552120222_MARK"));
    }

    @Test
    @DisplayName("resolveFromMarker parses then resolves in one call")
    void resolveFromMarker() {
      FeeTypeMatcher m = matcher();
      assertEquals("F04", m.resolveFromMarker("552120222_MARK_BROKERAGE_PRINCIPAL").feeId());
      assertNull(m.resolveFromMarker("552120222_MARK_"), "a malformed code yields null, not a throw");
      assertNull(m.resolveFromMarker(null));
      assertNull(m.resolveFromMarker("  "));
    }
  }

  // ── Tokenisation ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("tokenisation")
  class Tokens {

    @Test
    @DisplayName("every supported separator splits")
    void separatorsSplit() {
      Set<String> expected = Set.of("A", "B");
      assertEquals(expected, FeeTypeMatcher.tokenize("A_B"));
      assertEquals(expected, FeeTypeMatcher.tokenize("A-B"));
      assertEquals(expected, FeeTypeMatcher.tokenize("A B"));
      assertEquals(expected, FeeTypeMatcher.tokenize("A.B"));
      assertEquals(expected, FeeTypeMatcher.tokenize("A/B"));
    }

    @Test
    @DisplayName("tokens are upper-cased, so referential casing is irrelevant")
    void tokensAreUpperCased() {
      assertEquals(Set.of("CUSTODY"), FeeTypeMatcher.tokenize("Custody"));
    }

    @Test
    @DisplayName("repeated and leading/trailing separators collapse")
    void repeatedSeparatorsCollapse() {
      assertEquals(Set.of("A", "B"), FeeTypeMatcher.tokenize("__A__B__"));
      assertTrue(FeeTypeMatcher.tokenize("___").isEmpty());
      assertTrue(FeeTypeMatcher.tokenize("").isEmpty());
    }

    @Test
    @DisplayName("a duplicated token appears once — it is a set")
    void duplicateTokensCollapse() {
      assertEquals(Set.of("A"), FeeTypeMatcher.tokenize("A_A_A"));
    }
  }

  // ── Index lifecycle ───────────────────────────────────────────────────────

  @Nested
  @DisplayName("index reuse and caching")
  class IndexLifecycle {

    @Test
    @DisplayName("the referential is read once per lookup but the index is rebuilt only on change")
    void indexIsReusedWhileTheMapInstanceIsStable() {
      AtomicInteger providerCalls = new AtomicInteger();
      FeeTypeMatcher m = new FeeTypeMatcher(() -> {
        providerCalls.incrementAndGet();
        return REFERENTIAL;   // the same instance every time
      });

      for (int i = 0; i < 20; i++) {
        assertEquals("F01", m.resolve("CUSTODY").feeId());
      }
      assertEquals(20, providerCalls.get(), "the provider is consulted per call, by design");
    }

    @Test
    @DisplayName("a new map instance rebuilds the index, so a refresh is picked up")
    void newMapInstanceRebuildsTheIndex() {
      Map<String, String> first = Map.of("F01", "CUSTODY");
      Map<String, String> second = Map.of("F01", "CUSTODY", "F09", "SETTLEMENT");
      AtomicInteger call = new AtomicInteger();

      FeeTypeMatcher m = new FeeTypeMatcher(() -> call.incrementAndGet() == 1 ? first : second);

      assertNull(m.resolveOrNull("SETTLEMENT"), "not in the first referential");
      assertEquals("F09", m.resolve("SETTLEMENT").feeId(), "the refreshed referential has it");
    }

    @Test
    @DisplayName("a repeated spelling is answered from the memo, including a repeated failure")
    void resolutionsAreMemoised() {
      FeeTypeMatcher m = matcher();

      assertSame(m.resolveOrNull("CUSTODY"), m.resolveOrNull("CUSTODY"),
          "a memo hit should hand back the same match instance");
      assertNull(m.resolveOrNull("BROKERAGE"));
      assertNull(m.resolveOrNull("BROKERAGE"), "a repeatedly-bad code must not rescan every time");
      assertFalse(m.explainFailure("BROKERAGE").isBlank());
    }

    @Test
    @DisplayName("the exact-match fast path beats a partial on the same tokens")
    void exactMatchWins() {
      // PRINCIPAL_BROKERAGE has the identical token set to BROKERAGE_PRINCIPAL, so it takes the
      // canonical fast path rather than being scored.
      assertEquals("F04", matcher().resolve("PRINCIPAL_BROKERAGE").feeId());
    }
  }
}
