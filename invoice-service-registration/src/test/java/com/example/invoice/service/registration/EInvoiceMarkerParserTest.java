package com.example.invoice.service.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The marker is the pipeline's entry point — every downstream decision (which business's rules
 * run, which fee type is matched) hangs off getting this split right.
 */
class EInvoiceMarkerParserTest {

  @Test
  @DisplayName("splits <siren>_<BUSINESS>_<FEETYPE> and keeps underscores in the fee-type tail")
  void splitsThreeSegmentsPreservingFeeTypeUnderscores() {
    EInvoiceMarker m = EInvoiceMarkerParser.parse("552120222_MARK_BROKERAGE_PRINCIPAL");
    assertEquals("552120222", m.siren());
    assertEquals(Business.MARK, m.business());
    assertEquals("BROKERAGE_PRINCIPAL", m.feeType(),
        "the fee-type tail must survive intact — only the FIRST TWO underscores are separators");
  }

  @Test
  @DisplayName("simple single-token fee type")
  void simpleFeeType() {
    EInvoiceMarker m = EInvoiceMarkerParser.parse("552120222_MARK_CUSTODY");
    assertEquals(Business.MARK, m.business());
    assertEquals("CUSTODY", m.feeType());
  }

  @Test
  @DisplayName("unknown business token yields a null business rather than throwing")
  void unknownBusinessIsNullNotThrown() {
    EInvoiceMarker m = EInvoiceMarkerParser.parse("552120222_NOPE_CUSTODY");
    assertEquals("552120222", m.siren());
    assertNull(m.business(), "the orchestrator turns this into BUSINESS_UNKNOWN, not an exception");
    assertEquals("CUSTODY", m.feeType());
  }

  @Test
  @DisplayName("missing fee-type tail leaves feeType null")
  void missingFeeTypeTail() {
    EInvoiceMarker m = EInvoiceMarkerParser.parse("552120222_MARK");
    assertEquals(Business.MARK, m.business());
    assertNull(m.feeType());
  }

  @Test
  @DisplayName("null / blank input yields an all-null marker, never an exception")
  void nullAndBlankAreTolerated() {
    assertNull(EInvoiceMarkerParser.parse(null).siren());
    assertNull(EInvoiceMarkerParser.parse("   ").business());
  }

  @Test
  @DisplayName("business token matching is case-insensitive")
  void businessTokenIsCaseInsensitive() {
    assertEquals(Business.SGSS, EInvoiceMarkerParser.parse("123_sgss_CUSTODY").business());
  }
}
