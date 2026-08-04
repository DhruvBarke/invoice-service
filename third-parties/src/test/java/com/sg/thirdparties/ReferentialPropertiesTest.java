package com.sg.thirdparties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Where the referentials live, and what happens when that is not properly configured. */
class ReferentialPropertiesTest {

  @Test
  @DisplayName("trailing slashes are stripped, however many there are")
  void trailingSlashesAreStripped() {
    // A trailing slash here plus a leading one on the path gives //parties, which some gateways
    // route differently from /parties. Stripping once at construction beats every call site
    // remembering.
    ReferentialProperties props = new ReferentialProperties(
        "https://ref/api/", "https://fees//", "https://docs");

    assertEquals("https://ref/api", props.partyBaseUrl());
    assertEquals("https://fees", props.feeCategoryBaseUrl());
    assertEquals("https://docs", props.sgDocBaseUrl());
  }

  @Test
  @DisplayName("surrounding whitespace is trimmed")
  void whitespaceIsTrimmed() {
    // A stray space in a config file produces a URL that fails to parse at the first call, a
    // long way from the property that caused it.
    assertEquals("https://ref",
        new ReferentialProperties("  https://ref  ", "https://f", "https://d").partyBaseUrl());
  }

  @Test
  @DisplayName("a null or blank URL fails at construction, not at the first call")
  void absentUrlsFailFast() {
    // Failing at boot is much cheaper than failing on the first invoice of the day.
    assertThrows(NullPointerException.class,
        () -> new ReferentialProperties(null, "https://f", "https://d"));
    assertThrows(NullPointerException.class,
        () -> new ReferentialProperties("https://p", null, "https://d"));
    assertThrows(NullPointerException.class,
        () -> new ReferentialProperties("https://p", "https://f", null));

    assertThrows(IllegalArgumentException.class,
        () -> new ReferentialProperties("", "https://f", "https://d"));
    assertThrows(IllegalArgumentException.class,
        () -> new ReferentialProperties("   ", "https://f", "https://d"));
  }
}
