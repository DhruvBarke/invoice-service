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

import com.sg.domaininterface.model.provider.SsiDetails;
import com.sg.domaininterface.port.thirdparty.ReferentialUnavailableException;
import com.sg.thirdparties.RestCurrencyReferentialClient.CurrencyConverterResponse;
import com.sg.thirdparties.RestSsiReferentialClient.SsiDetailsResponse;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * The three referentials the enricher and the settlement rule consult.
 *
 * <p>One theme runs through all of them: <em>an empty answer and an unreachable service must
 * never look the same</em>. No rate published makes the euro amount {@code "NA"}; no instruction
 * on file holds a payment. Serving either of those on an outage turns one service being down into
 * a fact recorded against every invoice that passed through while it was.
 *
 * <p>The paths asserted here are the ones these clients call, not ones transcribed from a
 * contract — none was supplied. They are asserted anyway: a path that silently changes is a
 * client that quietly stops finding anything.
 */
class RestEnrichmentClientsTest {

  private static final ReferentialProperties PROPS = new ReferentialProperties(
      "https://p", "https://f", "https://d", "https://mail", "https://referential");

  private static URI capturedUri(RestTemplate template) {
    ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
    verify(template).exchange(uri.capture(), eq(HttpMethod.GET), any(),
        any(ParameterizedTypeReference.class));
    return uri.getValue();
  }

  // ── FX rates ──────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("the currency referential")
  class Rates {

    private RestTemplate template;
    private RestCurrencyReferentialClient client;

    Rates() {
      template = mock(RestTemplate.class);
      client = new RestCurrencyReferentialClient(template, PROPS);
    }

    private void answerWith(CurrencyConverterResponse body) {
      when(template.exchange(any(URI.class), eq(HttpMethod.GET), any(),
          eq(CurrencyConverterResponse.class))).thenReturn(ResponseEntity.ok(body));
    }

    private void failWith(RuntimeException failure) {
      when(template.exchange(any(URI.class), eq(HttpMethod.GET), any(),
          eq(CurrencyConverterResponse.class))).thenThrow(failure);
    }

    @Test
    @DisplayName("the mid value comes back as an exact decimal")
    void readsTheMidValue() {
      answerWith(new CurrencyConverterResponse("USD", "1.0850"));

      Optional<BigDecimal> rate = client.midRate(LocalDate.of(2026, 2, 28), "USD");

      // Parsed from the string rather than deserialised into a double: a rate that loses digits
      // before anything divides by it produces a euro amount nobody can reproduce.
      assertEquals(new BigDecimal("1.0850"), rate.orElseThrow());
    }

    @Test
    @DisplayName("the date and currency reach the query string")
    void queryCarriesBothCriteria() {
      answerWith(new CurrencyConverterResponse("USD", "1.0"));

      client.midRate(LocalDate.of(2026, 2, 28), "USD");

      ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
      verify(template).exchange(uri.capture(), eq(HttpMethod.GET), any(),
          eq(CurrencyConverterResponse.class));
      assertEquals("https://referential/currency-converter/mid-rate?date=2026-02-28&currency=USD",
          uri.getValue().toString());
    }

    @Test
    @DisplayName("a 404 is no rate for that day, not a failure")
    void notFoundIsEmpty() {
      // Rates are not published for every currency on every day.
      failWith(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "no rate",
          HttpHeaders.EMPTY, new byte[0], null));

      assertTrue(client.midRate(LocalDate.of(2026, 2, 28), "USD").isEmpty());
    }

    @Test
    @DisplayName("no body, a blank value or an unparseable one are all no rate")
    void unusableBodiesAreEmpty() {
      RestTemplate t = mock(RestTemplate.class);
      RestCurrencyReferentialClient c = new RestCurrencyReferentialClient(t, PROPS);
      when(t.exchange(any(URI.class), eq(HttpMethod.GET), any(),
          eq(CurrencyConverterResponse.class)))
          .thenReturn(ResponseEntity.ok(null))
          .thenReturn(ResponseEntity.ok(new CurrencyConverterResponse("USD", null)))
          .thenReturn(ResponseEntity.ok(new CurrencyConverterResponse("USD", "   ")))
          .thenReturn(ResponseEntity.ok(new CurrencyConverterResponse("USD", "not a number")));

      LocalDate day = LocalDate.of(2026, 2, 28);
      for (int i = 0; i < 4; i++) {
        // Raising on a typo would refuse a registration over the referential's data entry.
        assertTrue(c.midRate(day, "USD").isEmpty(), "attempt " + i);
      }
    }

    @Test
    @DisplayName("a 5xx is retryable, a 4xx that is not a 404 is not")
    void statusDecidesRetryability() {
      failWith(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "down",
          HttpHeaders.EMPTY, new byte[0], null));
      assertTrue(assertThrows(ReferentialUnavailableException.class,
          () -> client.midRate(LocalDate.of(2026, 2, 28), "USD")).isRetryable());

      RestTemplate refusing = mock(RestTemplate.class);
      when(refusing.exchange(any(URI.class), eq(HttpMethod.GET), any(),
          eq(CurrencyConverterResponse.class)))
          .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "no",
              HttpHeaders.EMPTY, new byte[0], null));
      assertFalse(assertThrows(ReferentialUnavailableException.class,
          () -> new RestCurrencyReferentialClient(refusing, PROPS)
              .midRate(LocalDate.of(2026, 2, 28), "USD")).isRetryable());
    }

    @Test
    @DisplayName("no response at all is retryable")
    void noResponseIsRetryable() {
      failWith(new ResourceAccessException("read timed out"));

      ReferentialUnavailableException thrown = assertThrows(ReferentialUnavailableException.class,
          () -> client.midRate(LocalDate.of(2026, 2, 28), "USD"));
      assertTrue(thrown.isRetryable());
      assertTrue(thrown.getMessage().contains("unreachable"));
    }

    @Test
    @DisplayName("collaborators and both arguments are mandatory")
    void mandatoryArguments() {
      assertThrows(NullPointerException.class,
          () -> new RestCurrencyReferentialClient(null, PROPS));
      assertThrows(NullPointerException.class,
          () -> new RestCurrencyReferentialClient(template, null));
      assertThrows(NullPointerException.class, () -> client.midRate(null, "USD"));
      assertThrows(NullPointerException.class,
          () -> client.midRate(LocalDate.of(2026, 2, 28), null));
    }
  }

  // ── Business calendar ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("the business calendar")
  class Calendar {

    private RestTemplate template;
    private RestBusinessCalendarClient client;

    Calendar() {
      template = mock(RestTemplate.class);
      client = new RestBusinessCalendarClient(template, PROPS);
    }

    private void answerWith(List<String> body) {
      when(template.exchange(any(URI.class), eq(HttpMethod.GET), any(),
          any(ParameterizedTypeReference.class))).thenReturn(ResponseEntity.ok(body));
    }

    @Test
    @DisplayName("the returned days are parsed")
    void parsesTheDays() {
      answerWith(List.of("2026-01-01", "2026-05-01"));

      assertEquals(List.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 1)),
          client.holidays(2026, "FRA"));
    }

    @Test
    @DisplayName("the query asks for three consecutive years")
    void asksForThreeYears() {
      // The date being adjusted can walk out of the year it started in, and asking per year
      // would mean a second call at exactly the moment the answer matters.
      answerWith(List.of());

      client.holidays(2026, "FRA");

      assertEquals("https://referential/calendar/holidays?fromYear=2026&years=3&countryCode=FRA",
          capturedUri(template).toString());
    }

    @Test
    @DisplayName("unreadable entries are dropped, not raised")
    void unreadableEntriesAreDropped() {
      // One malformed entry among a year's holidays would otherwise take out every registration
      // for that period. The cost of ignoring it is one day treated as open.
      answerWith(Arrays.asList("2026-01-01", null, "  ", "not a date", "2026-05-01"));

      assertEquals(List.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 1)),
          client.holidays(2026, "FRA"));
    }

    @Test
    @DisplayName("an empty calendar is a real answer")
    void emptyIsAnAnswer() {
      answerWith(List.of());
      assertTrue(client.holidays(2026, "FRA").isEmpty());
    }

    @Test
    @DisplayName("no body is a failure, not an empty calendar")
    void noBodyFails() {
      // Serving empty would move every re-attachment date onto a public holiday — a date that
      // looks entirely plausible and is wrong.
      when(template.exchange(any(URI.class), eq(HttpMethod.GET), any(),
          any(ParameterizedTypeReference.class))).thenReturn(ResponseEntity.ok(null));

      assertTrue(assertThrows(ReferentialUnavailableException.class,
          () -> client.holidays(2026, "FRA")).isRetryable());
    }

    @Test
    @DisplayName("a 5xx is retryable, a 4xx is not")
    void statusDecidesRetryability() {
      when(template.exchange(any(URI.class), eq(HttpMethod.GET), any(),
          any(ParameterizedTypeReference.class)))
          .thenThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "down",
              HttpHeaders.EMPTY, new byte[0], null));
      assertTrue(assertThrows(ReferentialUnavailableException.class,
          () -> client.holidays(2026, "FRA")).isRetryable());

      RestTemplate refusing = mock(RestTemplate.class);
      when(refusing.exchange(any(URI.class), eq(HttpMethod.GET), any(),
          any(ParameterizedTypeReference.class)))
          .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "no",
              HttpHeaders.EMPTY, new byte[0], null));
      assertFalse(assertThrows(ReferentialUnavailableException.class,
          () -> new RestBusinessCalendarClient(refusing, PROPS).holidays(2026, "FRA"))
          .isRetryable());
    }

    @Test
    @DisplayName("no response at all is retryable")
    void noResponseIsRetryable() {
      when(template.exchange(any(URI.class), eq(HttpMethod.GET), any(),
          any(ParameterizedTypeReference.class)))
          .thenThrow(new ResourceAccessException("connect timed out"));

      assertTrue(assertThrows(ReferentialUnavailableException.class,
          () -> client.holidays(2026, "FRA")).getMessage().contains("unreachable"));
    }

    @Test
    @DisplayName("collaborators and the country are mandatory")
    void mandatoryArguments() {
      assertThrows(NullPointerException.class,
          () -> new RestBusinessCalendarClient(null, PROPS));
      assertThrows(NullPointerException.class,
          () -> new RestBusinessCalendarClient(template, null));
      assertThrows(NullPointerException.class, () -> client.holidays(2026, null));
    }
  }

  // ── Settlement instructions ───────────────────────────────────────────────

  @Nested
  @DisplayName("the settlement-instruction referential")
  class Ssi {

    private RestTemplate template;
    private RestSsiReferentialClient client;

    Ssi() {
      template = mock(RestTemplate.class);
      client = new RestSsiReferentialClient(template, PROPS);
    }

    private void answerWith(List<SsiDetailsResponse> body) {
      when(template.exchange(any(URI.class), eq(HttpMethod.GET), any(),
          any(ParameterizedTypeReference.class))).thenReturn(ResponseEntity.ok(body));
    }

    @Test
    @DisplayName("instructions come back in the referential's order")
    void readsTheInstructions() {
      answerWith(List.of(
          new SsiDetailsResponse("FR76", "BNP", "BNPAFRPP"),
          new SsiDetailsResponse("DE89", "Deutsche", "DEUTDEFF")));

      List<SsiDetails> found = client.find("BDR-1", "EUR", "552120222", "F01");

      assertEquals(2, found.size());
      assertEquals("FR76", found.get(0).accountNumber());
      assertEquals("BNPAFRPP", found.get(0).swiftCode());
    }

    @Test
    @DisplayName("all four criteria reach the query string")
    void allCriteriaAreSent() {
      // Settlement is agreed at that granularity. A criterion dropped from the URL makes the
      // referential filter on less than it was asked to, and the extra accounts look like real
      // matches.
      answerWith(List.of());

      client.find("BDR-1", "EUR", "552120222", "F01");

      String uri = capturedUri(template).toString();
      assertTrue(uri.contains("providerId=BDR-1"), uri);
      assertTrue(uri.contains("currency=EUR"), uri);
      assertTrue(uri.contains("sgEntity=552120222"), uri);
      assertTrue(uri.contains("feeCategory=F01"), uri);
    }

    @Test
    @DisplayName("absent criteria are omitted, not sent empty")
    void absentCriteriaAreOmitted() {
      answerWith(List.of());

      client.find("BDR-1", null, "  ", "F01");

      String uri = capturedUri(template).toString();
      // An empty value reads upstream as "match the empty string", which matches nothing and
      // looks exactly like a provider with no instructions.
      assertFalse(uri.contains("currency="), uri);
      assertFalse(uri.contains("sgEntity="), uri);
      assertTrue(uri.contains("providerId=BDR-1"), uri);
    }

    @Test
    @DisplayName("entries with no account number are dropped")
    void entriesWithoutAnAccountAreDropped() {
      // The account code gates the whole comparison, so one without it can never match — and
      // would only inflate the count quoted in the alert.
      answerWith(Arrays.asList(
          new SsiDetailsResponse("FR76", "BNP", "BNPAFRPP"),
          new SsiDetailsResponse(null, "BNP", "BNPAFRPP"),
          new SsiDetailsResponse("  ", "BNP", "BNPAFRPP"),
          null));

      assertEquals(1, client.find("BDR-1", "EUR", "552120222", "F01").size());
    }

    @Test
    @DisplayName("a 404 or an empty body is nothing on file")
    void nothingOnFileIsEmpty() {
      when(template.exchange(any(URI.class), eq(HttpMethod.GET), any(),
          any(ParameterizedTypeReference.class)))
          .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "none",
              HttpHeaders.EMPTY, new byte[0], null));
      assertTrue(client.find("BDR-1", "EUR", "552120222", "F01").isEmpty());

      RestTemplate noBody = mock(RestTemplate.class);
      when(noBody.exchange(any(URI.class), eq(HttpMethod.GET), any(),
          any(ParameterizedTypeReference.class))).thenReturn(ResponseEntity.ok(null));
      assertTrue(new RestSsiReferentialClient(noBody, PROPS)
          .find("BDR-1", "EUR", "552120222", "F01").isEmpty());
    }

    @Test
    @DisplayName("an outage is raised, never served as nothing on file")
    void outageIsRaised() {
      // Nothing on file is what holds a payment. Collapsing an outage into it would hold every
      // payment in flight and look like an onboarding backlog.
      when(template.exchange(any(URI.class), eq(HttpMethod.GET), any(),
          any(ParameterizedTypeReference.class)))
          .thenThrow(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "down",
              HttpHeaders.EMPTY, new byte[0], null));

      ReferentialUnavailableException thrown = assertThrows(ReferentialUnavailableException.class,
          () -> client.find("BDR-1", "EUR", "552120222", "F01"));
      assertTrue(thrown.isRetryable());
      assertTrue(thrown.getMessage().contains("BDR-1"));
    }

    @Test
    @DisplayName("a 4xx that is not a 404 is not retryable")
    void clientErrorIsNotRetryable() {
      when(template.exchange(any(URI.class), eq(HttpMethod.GET), any(),
          any(ParameterizedTypeReference.class)))
          .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "no",
              HttpHeaders.EMPTY, new byte[0], null));

      assertFalse(assertThrows(ReferentialUnavailableException.class,
          () -> client.find("BDR-1", "EUR", "552120222", "F01")).isRetryable());
    }

    @Test
    @DisplayName("no response at all is retryable")
    void noResponseIsRetryable() {
      when(template.exchange(any(URI.class), eq(HttpMethod.GET), any(),
          any(ParameterizedTypeReference.class)))
          .thenThrow(new ResourceAccessException("read timed out"));

      assertTrue(assertThrows(ReferentialUnavailableException.class,
          () -> client.find("BDR-1", "EUR", "552120222", "F01")).isRetryable());
    }

    @Test
    @DisplayName("collaborators are mandatory")
    void mandatoryCollaborators() {
      assertThrows(NullPointerException.class, () -> new RestSsiReferentialClient(null, PROPS));
      assertThrows(NullPointerException.class,
          () -> new RestSsiReferentialClient(template, null));
    }
  }
}
