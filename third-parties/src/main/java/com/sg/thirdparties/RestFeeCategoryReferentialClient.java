package com.sg.thirdparties;

import com.sg.domaininterface.port.thirdparty.FeeCategoryReferentialService;
import com.sg.domaininterface.port.thirdparty.ReferentialUnavailableException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * {@link FeeCategoryReferentialService} over the referential's HTTP API.
 *
 * <p>Fetches the whole active fee-type set in one call. The matcher scores a token against every
 * candidate to find the closest, so it needs the full set rather than a lookup, and the set is
 * small enough that one call is cheaper than many.
 *
 * <p><b>A failure is raised, never served as an empty map.</b> An empty referential resolves
 * nothing, which would leave every invoice in flight with an unresolved fee type — a whole
 * batch refused, looking like bad data from the senders rather than an outage here.
 */
public final class RestFeeCategoryReferentialClient implements FeeCategoryReferentialService {

  private static final String REFERENTIAL = "fee-category";

  private static final ParameterizedTypeReference<List<FeeCategoryResponse>> FEE_LIST =
      new ParameterizedTypeReference<>() {};

  /**
   * One entry as the referential returns it.
   *
   * <p>Its own type rather than a {@code Map<String, String>} because the referential's payload
   * carries more than the two fields used here, and a map would silently accept any shape at
   * all — including a response that changed and no longer contains what is needed.
   */
  public record FeeCategoryResponse(String feeId, String feeType, Boolean active) {}

  private final RestTemplate restTemplate;
  private final ReferentialProperties properties;

  public RestFeeCategoryReferentialClient(RestTemplate restTemplate,
                                          ReferentialProperties properties) {
    this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  @Override
  public Map<String, String> findAllFeeTypes() {
    URI uri = UriComponentsBuilder
        .fromUriString(properties.feeCategoryBaseUrl() + "/fee-categories")
        .queryParam("active", "true")
        .build().encode().toUri();

    try {
      ResponseEntity<List<FeeCategoryResponse>> response =
          restTemplate.exchange(uri, HttpMethod.GET, null, FEE_LIST);

      List<FeeCategoryResponse> body = response.getBody();
      if (body == null) {
        throw new ReferentialUnavailableException(REFERENTIAL,
            "fee-category referential returned no body", true, null);
      }
      return toMap(body);

    } catch (HttpStatusCodeException e) {
      throw new ReferentialUnavailableException(REFERENTIAL,
          REFERENTIAL + " returned " + e.getStatusCode().value(),
          e.getStatusCode().is5xxServerError(), e);
    } catch (RestClientException e) {
      throw new ReferentialUnavailableException(REFERENTIAL,
          "fee-category referential unreachable: " + e.getMessage(), true, e);
    }
  }

  /**
   * {@code feeId -> feeType}, in the referential's order.
   *
   * <p>Order is kept because the matcher's tie-breaking is positional: reordering would silently
   * change which of two equally-good candidates wins, and nothing would look wrong.
   *
   * <p>Entries missing an id or a type are dropped. A half-populated entry cannot be matched
   * against or recorded, and letting one through would put a null key in the map the matcher
   * iterates.
   */
  private static Map<String, String> toMap(List<FeeCategoryResponse> entries) {
    Map<String, String> out = new LinkedHashMap<>();
    for (FeeCategoryResponse entry : entries) {
      if (entry == null || entry.feeId() == null || entry.feeType() == null) {
        continue;
      }
      if (Boolean.FALSE.equals(entry.active())) {
        // Belt and braces: the query already asks for active entries only, but a referential
        // that ignores the filter should not quietly reintroduce retired fee types.
        continue;
      }
      out.put(entry.feeId(), entry.feeType());
    }
    return out;
  }
}
