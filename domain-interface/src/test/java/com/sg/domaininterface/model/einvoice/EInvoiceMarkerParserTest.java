package com.sg.domaininterface.model.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The receiver endpoint marker, {@code <siren>_<BUSINESS>_<FEETYPE>}.
 *
 * <p>This is the single input that decides which rule set an invoice is judged against, so a
 * misread here does not fail loudly — it quietly runs the wrong checks, or none.
 */
class EInvoiceMarkerParserTest {

  @Test
  @DisplayName("a well-formed marker yields all three parts")
  void wellFormed() {
    EInvoiceMarker m = EInvoiceMarkerParser.parse("552120222_MARK_CUSTODY");
    assertEquals("552120222", m.siren());
    assertEquals(Business.MARK, m.business());
    assertEquals("CUSTODY", m.feeType());
    assertEquals("552120222_MARK_CUSTODY", m.rawValue());
  }

  @Test
  @DisplayName("the fee type keeps its own underscores")
  void feeTypeMayContainUnderscores() {
    assertEquals("BROKERAGE_PRINCIPAL",
        EInvoiceMarkerParser.parse("552120222_MARK_BROKERAGE_PRINCIPAL").feeType());
  }

  @ParameterizedTest(name = "[{0}] has no usable fee type")
  @ValueSource(strings = {"552120222_MARK_", "552120222_MARK_   ", "552120222_MARK_ "})
  @DisplayName("a present-but-blank fee-type tail is no fee type at all")
  void blankFeeTypeIsNull(String raw) {
    // Three inputs, and they do NOT all take the same path — which is the point of listing
    // them. The first two are equivalent because the parser trims first, so trailing ASCII
    // spaces are gone before the split and the marker ends on its separator. The third is
    // U+2003 EM SPACE: above U+0020, so trim() leaves it alone, and it survives to become a
    // fee-type tail that is non-empty but still blank. Only that input reaches the isBlank
    // check, and without it that guard is dead code no test would notice losing.
    //
    // The consequence of any of them slipping through is the same: the matcher would be asked
    // to resolve a blank fee type and the invoice would be reported as having an unresolvable
    // fee rather than a malformed marker, sending the sender to the referential to look for a
    // fee they never named.
    EInvoiceMarker m = EInvoiceMarkerParser.parse(raw);
    assertNull(m.feeType());
    assertEquals(Business.MARK, m.business(),
        "the business sits between the separators and is still readable");
  }

  @Test
  @DisplayName("a marker with no second separator still yields the business")
  void singleSeparator() {
    EInvoiceMarker m = EInvoiceMarkerParser.parse("552120222_MARK");
    assertEquals(Business.MARK, m.business());
    assertNull(m.feeType());
  }

  @ParameterizedTest(name = "[{0}] yields nothing")
  @ValueSource(strings = {"", "   ", "552120222"})
  @DisplayName("an unusable endpoint yields an empty marker rather than throwing")
  void unusableInput(String raw) {
    EInvoiceMarker m = EInvoiceMarkerParser.parse(raw);
    assertNull(m.business());
    assertNull(m.feeType());
  }

  @Test
  @DisplayName("a null endpoint is not an error either")
  void nullInput() {
    assertNull(EInvoiceMarkerParser.parse(null).business());
  }

  @Test
  @DisplayName("an unknown business token resolves to null, not to a wrong business")
  void unknownBusiness() {
    EInvoiceMarker m = EInvoiceMarkerParser.parse("552120222_NOTABUSINESS_CUSTODY");
    assertNull(m.business(), "guessing here would run another business's rule set");
    assertEquals("CUSTODY", m.feeType());
  }
}
