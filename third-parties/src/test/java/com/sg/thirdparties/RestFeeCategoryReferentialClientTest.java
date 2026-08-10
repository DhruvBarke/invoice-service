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

import com.sg.domaininterface.port.thirdparty.ReferentialUnavailableException;
import com.sg.thirdparties.RestFeeCategoryReferentialClient.FeeCategoryResponse;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
 * Loading the fee-type referential.
 *
 * <p>The failure behaviour carries most of the weight here. An empty referential resolves no fee
 * type at all, so serving one on error would refuse every invoice in flight and look like the
 * senders had sent bad data.
 */
class RestFeeCategoryReferentialClientTest {

  private RestTemplate restTemplate;
  private RestFeeCategoryReferentialClient client;

  @BeforeEach
  void setUp() {
    restTemplate = mock(RestTemplate.class);
    client = new RestFeeCategoryReferentialClient(restTemplate,
        new ReferentialProperties("https://parties", "https://referential/fees", "https://docs", "https://mail"));
  }

  private void answerWith(List<FeeCategoryResponse> body) {
    when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(),
        any(ParameterizedTypeReference.class))).thenReturn(ResponseEntity.ok(body));
  }

  @Test
  @DisplayName("the active fee types load as feeId to feeType, in order")
  void loadsInOrder() {
    answerWith(List.of(
        new FeeCategoryResponse("F01", "CUSTODY", true),
        new FeeCategoryResponse("F02", "BROKERAGE_PRINCIPAL", true)));

    Map<String, String> loaded = client.findAllFeeTypes();

    assertEquals(2, loaded.size());
    assertEquals("CUSTODY", loaded.get("F01"));
    assertEquals(List.of("F01", "F02"), List.copyOf(loaded.keySet()),
        "the matcher's tie-breaking is positional, so a reordering would silently change which "
            + "of two equally-good candidates wins");
  }

  @Test
  @DisplayName("the query asks for active entries only")
  void queriesActiveOnly() {
    answerWith(List.of());

    client.findAllFeeTypes();

    ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
    verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.GET), any(),
        any(ParameterizedTypeReference.class));
    assertEquals("https://referential/fees/fee-categories?active=true", uri.getValue().toString());
  }

  @Test
  @DisplayName("half-populated and retired entries are dropped")
  void unusableEntriesAreDropped() {
    answerWith(Arrays.asList(
        new FeeCategoryResponse("F01", "CUSTODY", true),
        new FeeCategoryResponse(null, "NO_ID", true),
        new FeeCategoryResponse("F03", null, true),
        new FeeCategoryResponse("F04", "RETIRED", false),
        null));

    Map<String, String> loaded = client.findAllFeeTypes();

    // An entry missing either half cannot be matched against or recorded, and a null key would
    // sit in the map the matcher iterates.
    assertEquals(Map.of("F01", "CUSTODY"), loaded);
    assertFalse(loaded.containsValue("RETIRED"),
        "the query already filters, but a referential that ignores the filter must not "
            + "reintroduce retired fee types");
  }

  @Test
  @DisplayName("an empty referential is an empty map")
  void emptyIsEmpty() {
    answerWith(List.of());
    assertTrue(client.findAllFeeTypes().isEmpty());
  }

  @Test
  @DisplayName("no body is a failure, not an empty referential")
  void noBodyFails() {
    when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(),
        any(ParameterizedTypeReference.class))).thenReturn(ResponseEntity.ok(null));

    ReferentialUnavailableException thrown =
        assertThrows(ReferentialUnavailableException.class, client::findAllFeeTypes);
    assertTrue(thrown.isRetryable());
    assertEquals("fee-category", thrown.referential());
  }

  @Test
  @DisplayName("a 5xx is retryable, a 4xx is not")
  void statusDecidesRetryability() {
    when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(),
        any(ParameterizedTypeReference.class)))
        .thenThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "down",
            HttpHeaders.EMPTY, new byte[0], null));
    assertTrue(assertThrows(ReferentialUnavailableException.class, client::findAllFeeTypes)
        .isRetryable());

    RestTemplate refusing = mock(RestTemplate.class);
    when(refusing.exchange(any(URI.class), eq(HttpMethod.GET), any(),
        any(ParameterizedTypeReference.class)))
        .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "no",
            HttpHeaders.EMPTY, new byte[0], null));
    RestFeeCategoryReferentialClient forbidden = new RestFeeCategoryReferentialClient(refusing,
        new ReferentialProperties("https://a", "https://b", "https://c", "https://mail"));

    // A 403 will be a 403 next time too. Retrying it adds load to something already telling us
    // the problem is on this side of the call.
    assertFalse(assertThrows(ReferentialUnavailableException.class, forbidden::findAllFeeTypes)
        .isRetryable());
  }

  @Test
  @DisplayName("no response at all is retryable")
  void noResponseIsRetryable() {
    when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(),
        any(ParameterizedTypeReference.class)))
        .thenThrow(new ResourceAccessException("read timed out"));

    ReferentialUnavailableException thrown =
        assertThrows(ReferentialUnavailableException.class, client::findAllFeeTypes);
    assertTrue(thrown.isRetryable());
    assertTrue(thrown.getMessage().contains("unreachable"));
  }

  @Test
  @DisplayName("collaborators are mandatory")
  void mandatoryCollaborators() {
    ReferentialProperties props = new ReferentialProperties("https://a", "https://b", "https://c", "https://mail");
    assertThrows(NullPointerException.class,
        () -> new RestFeeCategoryReferentialClient(null, props));
    assertThrows(NullPointerException.class,
        () -> new RestFeeCategoryReferentialClient(restTemplate, null));
  }
}
