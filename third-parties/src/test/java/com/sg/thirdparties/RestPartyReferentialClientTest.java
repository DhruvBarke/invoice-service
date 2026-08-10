package com.sg.thirdparties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.model.referential.PartySearchRequest;
import com.sg.domaininterface.port.out.PartyRegistrationUnavailableException;
import com.sg.domaininterface.port.out.UnavailabilityReason;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * The URL this client builds, and how it reads what comes back.
 *
 * <p>The URL is the interesting part. A criterion that should have been in the query string and
 * is not produces a confident, wrong answer — the referential filters on less than it was asked
 * to and returns parties that do not match. That failure is invisible downstream, so it is
 * asserted on directly here.
 */
class RestPartyReferentialClientTest {

  private RestTemplate restTemplate;
  private RestPartyReferentialClient client;

  @BeforeEach
  void setUp() {
    restTemplate = mock(RestTemplate.class);
    client = new RestPartyReferentialClient(restTemplate,
        new ReferentialProperties("https://referential/api", "https://fees", "https://docs", "https://mail", "https://common"));
  }

  private void answerWith(List<PartyRegistrationDetails> body) {
    when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(),
        any(ParameterizedTypeReference.class))).thenReturn(ResponseEntity.ok(body));
  }

  private URI capturedUri() {
    ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
    verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.GET), any(),
        any(ParameterizedTypeReference.class));
    return uri.getValue();
  }

  private static PartyRegistrationDetails party(String siren) {
    return new PartyRegistrationDetails(null, null, null, null, null, null,
        "G1", "Acme SA", "ACME", siren, "12345678900012", List.of());
  }

  // ── URL construction ──────────────────────────────────────────────────────

  @Test
  @DisplayName("one criterion becomes one query parameter")
  void singleCriterion() {
    answerWith(List.of(party("123456789")));

    client.search(PartySearchRequest.bySiren("123456789"));

    assertEquals("https://referential/api/parties?siren=123456789", capturedUri().toString());
  }

  @Test
  @DisplayName("several criteria all reach the query string")
  void severalCriteria() {
    answerWith(List.of());

    client.search(new PartySearchRequest("123456789", null, null, null, null, "FR"));

    String uri = capturedUri().toString();
    assertTrue(uri.contains("siren=123456789"), uri);
    assertTrue(uri.contains("countryCode=FR"),
        "a criterion dropped from the URL makes the referential filter on less than it was "
            + "asked to, and the extra matches look like real ones");
  }

  @Test
  @DisplayName("absent criteria are omitted, not sent empty")
  void absentCriteriaAreOmitted() {
    answerWith(List.of());

    client.search(PartySearchRequest.bySiret("12345678900012"));

    String uri = capturedUri().toString();
    assertEquals("https://referential/api/parties?siret=12345678900012", uri);
    // An empty value reads as "match the empty string" upstream, which matches nothing.
    assertFalse(uri.contains("siren="), uri);
    assertFalse(uri.contains("mnemonic="), uri);
  }

  @Test
  @DisplayName("values needing encoding are encoded once")
  void valuesAreEncoded() {
    answerWith(List.of());

    // Party names routinely contain spaces and ampersands. Concatenating them into a URL
    // truncates the query at the ampersand and silently drops every later criterion.
    client.search(new PartySearchRequest(null, null, null, null, "Barnes & Noble SA", "FR"));

    String uri = capturedUri().toString();
    assertTrue(uri.contains("Barnes%20&%20Noble%20SA") || uri.contains("Barnes+%26+Noble+SA")
        || uri.contains("Barnes%20%26%20Noble%20SA"), uri);
    assertTrue(uri.contains("countryCode=FR"),
        "the criterion after the ampersand must survive");
  }

  @Test
  @DisplayName("a base URL with a trailing slash does not produce a double slash")
  void trailingSlashIsNormalised() {
    RestTemplate template = mock(RestTemplate.class);
    when(template.exchange(any(URI.class), eq(HttpMethod.GET), any(),
        any(ParameterizedTypeReference.class))).thenReturn(ResponseEntity.ok(List.of()));

    new RestPartyReferentialClient(template,
        new ReferentialProperties("https://referential/api/", "https://f", "https://d", "https://mail", "https://common"))
        .search(PartySearchRequest.bySiren("123456789"));

    ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
    verify(template).exchange(uri.capture(), eq(HttpMethod.GET), any(),
        any(ParameterizedTypeReference.class));
    assertFalse(uri.getValue().toString().contains("api//parties"),
        "some gateways route //parties differently from /parties");
  }

  // ── Reading the response ──────────────────────────────────────────────────

  @Test
  @DisplayName("matches come back in the referential's order")
  void matchesAreReturned() {
    answerWith(List.of(party("111111111"), party("222222222")));

    List<PartyRegistrationDetails> found = client.search(PartySearchRequest.bySiren("111111111"));

    assertEquals(2, found.size());
    assertEquals("111111111", found.get(0).siren());
  }

  @Test
  @DisplayName("a 404 is no matches, not a failure")
  void notFoundIsEmpty() {
    // A party that is not registered is a real and common state. Raising here would turn every
    // unregistered supplier into an outage.
    when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(),
        any(ParameterizedTypeReference.class)))
        .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
            HttpHeaders.EMPTY, new byte[0], null));

    assertTrue(client.search(PartySearchRequest.bySiren("999999999")).isEmpty());
  }

  @Test
  @DisplayName("a 2xx with no body is no matches")
  void emptyBodyIsEmpty() {
    when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(),
        any(ParameterizedTypeReference.class))).thenReturn(ResponseEntity.ok(null));

    assertTrue(client.search(PartySearchRequest.bySiren("123456789")).isEmpty());
  }

  @Test
  @DisplayName("the returned list does not change under the caller")
  void resultIsCopied() {
    List<PartyRegistrationDetails> mutable = new java.util.ArrayList<>(List.of(party("1")));
    answerWith(mutable);

    List<PartyRegistrationDetails> found = client.search(PartySearchRequest.bySiren("123456789"));
    mutable.clear();

    assertEquals(1, found.size());
  }

  // ── Failures ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("a 5xx is retryable")
  void serverErrorIsRetryable() {
    when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(),
        any(ParameterizedTypeReference.class)))
        .thenThrow(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway",
            HttpHeaders.EMPTY, new byte[0], null));

    PartyRegistrationUnavailableException thrown =
        assertThrows(PartyRegistrationUnavailableException.class,
            () -> client.search(PartySearchRequest.bySiren("123456789")));

    assertTrue(thrown.isRetryable(), "the referential is having a bad moment, not refusing us");
    assertTrue(thrown.getMessage().contains("502"));
  }

  @Test
  @DisplayName("a 4xx that is not a 404 is not retryable")
  void clientErrorIsNotRetryable() {
    // The request is wrong and will be exactly as wrong next time. Retrying only adds load to
    // something already telling us the problem is on this side.
    when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(),
        any(ParameterizedTypeReference.class)))
        .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
            HttpHeaders.EMPTY, new byte[0], null));

    PartyRegistrationUnavailableException thrown =
        assertThrows(PartyRegistrationUnavailableException.class,
            () -> client.search(PartySearchRequest.bySiren("123456789")));

    assertFalse(thrown.isRetryable());
    assertEquals(UnavailabilityReason.INVALID_IDENTIFIER, thrown.reason());
  }

  @Test
  @DisplayName("no response at all is retryable, and names the criteria")
  void noResponseIsRetryable() {
    when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(),
        any(ParameterizedTypeReference.class)))
        .thenThrow(new ResourceAccessException("connect timed out"));

    PartyRegistrationUnavailableException thrown =
        assertThrows(PartyRegistrationUnavailableException.class,
            () -> client.search(PartySearchRequest.bySiren("123456789")));

    assertTrue(thrown.isRetryable());
    assertTrue(thrown.getMessage().contains("siren=123456789"),
        "an alert that cannot say what was being looked up is one someone has to reproduce");
  }

  // ── Contract ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("collaborators and the request are mandatory")
  void mandatoryArguments() {
    ReferentialProperties props =
        new ReferentialProperties("https://a", "https://b", "https://c", "https://mail", "https://common");
    assertThrows(NullPointerException.class, () -> new RestPartyReferentialClient(null, props));
    assertThrows(NullPointerException.class,
        () -> new RestPartyReferentialClient(restTemplate, null));
    assertThrows(NullPointerException.class, () -> client.search(null));
  }

  @Test
  @DisplayName("the client names the referential it speaks to")
  void namesItsReferential() {
    assertEquals("party-registration", RestPartyReferentialClient.referentialName());
  }
}
