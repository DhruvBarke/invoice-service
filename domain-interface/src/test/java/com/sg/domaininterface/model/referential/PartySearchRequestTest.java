package com.sg.domaininterface.model.referential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domaininterface.model.party.RegistrationType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What becomes a query parameter, and what does not.
 *
 * <p>This decides the referential URL. A criterion that should be in the query string and is not
 * makes the referential filter on less than it was asked to — it answers with parties that do
 * not match, confidently, and nothing downstream can tell.
 */
class PartySearchRequestTest {

  @Test
  @DisplayName("only populated criteria become parameters")
  void onlyPopulatedCriteria() {
    Map<String, String> params = PartySearchRequest.bySiren("123456789").toQueryParameters();

    assertEquals(Map.of("siren", "123456789"), params);
    // An empty value reads upstream as "match the empty string", which matches nothing.
    assertTrue(!params.containsKey("siret"));
  }

  @Test
  @DisplayName("blank criteria count as absent")
  void blankIsAbsent() {
    Map<String, String> params =
        new PartySearchRequest("123456789", "", "   ", null, null, "FR").toQueryParameters();

    assertEquals(List.of("siren", "countryCode"), List.copyOf(params.keySet()));
  }

  @Test
  @DisplayName("values are trimmed")
  void valuesAreTrimmed() {
    // A stray space survives URL encoding as %20 and turns into a criterion that matches
    // nothing, which reads as "no such party".
    assertEquals("123456789",
        new PartySearchRequest(" 123456789 ", null, null, null, null, null)
            .toQueryParameters().get("siren"));
  }

  @Test
  @DisplayName("parameter order is stable")
  void orderIsStable() {
    // The URL is a cache key upstream. Two requests with the same criteria in a different order
    // would otherwise miss each other's cached response for no reason.
    PartySearchRequest request =
        new PartySearchRequest("1", "2", "3", "4", "5", "6");

    assertEquals(List.of("siren", "siret", "goldenBdrId", "mnemonic", "name", "countryCode"),
        List.copyOf(request.toQueryParameters().keySet()));
    assertEquals(List.copyOf(request.toQueryParameters().keySet()),
        List.copyOf(request.toQueryParameters().keySet()));
  }

  @Test
  @DisplayName("a request with no criteria at all is refused")
  void emptyRequestIsRefused() {
    // An unfiltered request asks the referential for every party it holds. That is not a lookup,
    // and failing here is far cheaper than a timeout downstream.
    assertThrows(IllegalArgumentException.class,
        () -> new PartySearchRequest(null, null, null, null, null, null));
  }

  @Test
  @DisplayName("the registration type decides which criterion the id becomes")
  void registrationTypeRouting() {
    // SIREN and SIRET are different columns upstream. A SIRET sent as a siren returns a
    // confident empty answer rather than an error.
    assertEquals(Map.of("siren", "123456789"),
        PartySearchRequest.byRegistration("123456789", RegistrationType.SIREN)
            .toQueryParameters());
    assertEquals(Map.of("siret", "12345678900012"),
        PartySearchRequest.byRegistration("12345678900012", RegistrationType.SIRET)
            .toQueryParameters());
    assertThrows(NullPointerException.class,
        () -> PartySearchRequest.byRegistration("123456789", null));
  }

  @Test
  @DisplayName("the golden id factory sets only that criterion")
  void byGoldenBdrId() {
    assertEquals(Map.of("goldenBdrId", "G1"),
        PartySearchRequest.byGoldenBdrId("G1").toQueryParameters());
  }

  @Test
  @DisplayName("two requests asking the same thing are equal, whatever built them")
  void equalityIsByCriteria() {
    assertEquals(PartySearchRequest.bySiren("123456789"),
        PartySearchRequest.byRegistration("123456789", RegistrationType.SIREN));
    assertEquals(PartySearchRequest.bySiren("123456789").hashCode(),
        PartySearchRequest.bySiren(" 123456789 ").hashCode(),
        "trimming happens before comparison, so a padded value is the same request");
    assertNotEquals(PartySearchRequest.bySiren("123456789"),
        PartySearchRequest.bySiret("123456789"));
    assertNotEquals(PartySearchRequest.bySiren("123456789"), "not a request");
  }

  @Test
  @DisplayName("describe and toString render the criteria, not the whole record")
  void rendering() {
    PartySearchRequest request = new PartySearchRequest("123456789", null, null, null, null, "FR");

    assertEquals("siren=123456789&countryCode=FR", request.describe());
    assertEquals("PartySearchRequest[siren=123456789&countryCode=FR]", request.toString());
  }
}
