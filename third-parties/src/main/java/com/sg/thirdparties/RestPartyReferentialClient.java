package com.sg.thirdparties;

import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.model.referential.PartySearchRequest;
import com.sg.domaininterface.port.out.PartyRegistrationUnavailableException;
import com.sg.domaininterface.port.out.UnavailabilityReason;
import com.sg.domaininterface.port.thirdparty.PartyReferentialService;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * {@link PartyReferentialService} over the referential's HTTP API.
 *
 * <p><b>The URL is built from whichever criteria the request carries.</b> A
 * {@link PartySearchRequest} with only a SIREN becomes {@code ?siren=…}; one with a SIREN and a
 * country becomes {@code ?siren=…&countryCode=…}. Absent criteria are omitted rather than sent
 * empty, because the referential reads an empty value as "match the empty string" and would
 * answer nothing.
 *
 * <p><b>404 is an empty result, not a failure.</b> A party that is not registered is a real and
 * common state, and the caller decides what it means for the invoice. Every other non-2xx is an
 * outage: turning one into an empty list would refuse a batch of invoices for a fault on our
 * side and make it look like a data problem.
 */
public final class RestPartyReferentialClient implements PartyReferentialService {

  private static final String REFERENTIAL = "party-registration";

  private static final ParameterizedTypeReference<List<PartyRegistrationDetails>> PARTY_LIST =
      new ParameterizedTypeReference<>() {};

  private final RestTemplate restTemplate;
  private final ReferentialProperties properties;

  public RestPartyReferentialClient(RestTemplate restTemplate, ReferentialProperties properties) {
    this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  @Override
  public List<PartyRegistrationDetails> search(PartySearchRequest request) {
    Objects.requireNonNull(request, "request");
    URI uri = buildUri(request);

    try {
      ResponseEntity<List<PartyRegistrationDetails>> response =
          restTemplate.exchange(uri, HttpMethod.GET, null, PARTY_LIST);

      List<PartyRegistrationDetails> body = response.getBody();
      // A 2xx with no body is the referential telling us it found nothing, in a shape the
      // deserialiser could not turn into a list. Treated as no matches, not as a fault.
      return body == null ? List.of() : List.copyOf(body);

    } catch (HttpStatusCodeException e) {
      if (e.getStatusCode().value() == 404) {
        return List.of();
      }
      throw unavailable(request, e.getStatusCode(), e);
    } catch (RestClientException e) {
      // No response at all: timeout, connection refused, unreadable body. Always worth retrying
      // — none of these say anything about whether the party exists.
      throw new PartyRegistrationUnavailableException(
          UnavailabilityReason.UPSTREAM_UNAVAILABLE, "SIREN", request.describe(),
          "party referential unreachable looking up " + request.describe()
              + ": " + e.getMessage(), null, e);
    }
  }

  /**
   * {@code {base}/parties?…} with one parameter per populated criterion.
   *
   * <p>Built through {@link UriComponentsBuilder} rather than string concatenation so a name or
   * mnemonic containing a space, an ampersand or a non-ASCII character is encoded once and
   * correctly. Party names routinely contain all three.
   */
  private URI buildUri(PartySearchRequest request) {
    UriComponentsBuilder builder =
        UriComponentsBuilder.fromUriString(properties.partyBaseUrl() + "/parties");
    for (Map.Entry<String, String> criterion : request.toQueryParameters().entrySet()) {
      builder.queryParam(criterion.getKey(), criterion.getValue());
    }
    return builder.build().encode().toUri();
  }

  /**
   * A non-2xx that is not a 404.
   *
   * <p>4xx means the request was wrong and will be exactly as wrong next time; 5xx means the
   * referential is having a bad moment. Only the second is worth another attempt, and the
   * distinction is carried on the reason rather than left to the caller to infer from a message.
   */
  private PartyRegistrationUnavailableException unavailable(
      PartySearchRequest request, HttpStatusCode status, Throwable cause) {
    UnavailabilityReason reason = status.is4xxClientError()
        ? UnavailabilityReason.INVALID_IDENTIFIER
        : UnavailabilityReason.UPSTREAM_UNAVAILABLE;
    return new PartyRegistrationUnavailableException(
        reason, "SIREN", request.describe(),
        REFERENTIAL + " returned " + status.value() + " looking up " + request.describe()
            + ": " + cause.getMessage(), null, cause);
  }

  /** Visible for the wiring: which referential this client speaks to. */
  public static String referentialName() {
    return REFERENTIAL;
  }
}
