package com.sg.thirdparties;

import com.sg.domaininterface.model.provider.SsiDetails;
import com.sg.domaininterface.port.thirdparty.ReferentialUnavailableException;
import com.sg.domaininterface.port.thirdparty.SsiReferentialService;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * {@link SsiReferentialService} over the referential's HTTP API.
 *
 * <p><b>The path is assumed.</b> The manual path calls
 * {@code referentialServiceApi.getSsiDetails(providerId, currency, sgEntity, feeCategory)} and
 * reads {@code getAccountNumber()}, {@code getBankName()} and {@code getSwiftCode()} off each
 * result — four inputs and three fields, but no URL. {@link #PATH} and
 * {@link SsiDetailsResponse} are what change when the real contract is known.
 *
 * <p><b>An outage is never served as an empty list.</b> No instructions on file is what holds a
 * payment; collapsing an outage into that would hold every payment in flight and look like an
 * onboarding backlog rather than a service being down.
 */
public final class RestSsiReferentialClient implements SsiReferentialService {

  private static final String REFERENTIAL = "settlement-instructions";

  private static final String PATH = "/settlement-instructions";

  private static final ParameterizedTypeReference<List<SsiDetailsResponse>> SSI_LIST =
      new ParameterizedTypeReference<>() {};

  /**
   * One instruction as the referential returns it.
   *
   * <p>Separate from {@link SsiDetails} so the wire shape can change without the domain model
   * following it, and so a response carrying more fields than these three is not silently
   * accepted as though it were the right one.
   */
  public record SsiDetailsResponse(String accountNumber, String bankName, String swiftCode) {}

  private final RestTemplate restTemplate;
  private final ReferentialProperties properties;

  public RestSsiReferentialClient(RestTemplate restTemplate, ReferentialProperties properties) {
    this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  @Override
  public List<SsiDetails> find(String providerId, String currency, String sgEntity,
                               String feeCategory) {
    UriComponentsBuilder uri = UriComponentsBuilder
        .fromUriString(properties.commonBaseUrl() + PATH);
    // Absent criteria are omitted rather than sent empty: an empty value reads upstream as "match
    // the empty string", which matches nothing and looks like a provider with no instructions.
    addIfPresent(uri, "providerId", providerId);
    addIfPresent(uri, "currency", currency);
    addIfPresent(uri, "sgEntity", sgEntity);
    addIfPresent(uri, "feeCategory", feeCategory);

    try {
      ResponseEntity<List<SsiDetailsResponse>> response = restTemplate.exchange(
          uri.build().encode().toUri(), HttpMethod.GET, null, SSI_LIST);

      List<SsiDetailsResponse> body = response.getBody();
      return body == null ? List.of() : toDomain(body);

    } catch (HttpStatusCodeException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        // A provider with nothing on file is a real state, and a common one during onboarding.
        return List.of();
      }
      throw new ReferentialUnavailableException(REFERENTIAL,
          REFERENTIAL + " returned " + e.getStatusCode().value() + " for provider " + providerId,
          e.getStatusCode().is5xxServerError(), e);
    } catch (RestClientException e) {
      throw new ReferentialUnavailableException(REFERENTIAL,
          "settlement-instruction referential unreachable: " + e.getMessage(), true, e);
    }
  }

  private static void addIfPresent(UriComponentsBuilder uri, String name, String value) {
    if (value != null && !value.isBlank()) {
      uri.queryParam(name, value);
    }
  }

  /**
   * Map onto the domain type, keeping the referential's order.
   *
   * <p>Entries with no account number are dropped: the account code gates the whole comparison, so
   * one without it can never match and would only inflate the count quoted in an alert.
   */
  private static List<SsiDetails> toDomain(List<SsiDetailsResponse> entries) {
    List<SsiDetails> out = new ArrayList<>(entries.size());
    for (SsiDetailsResponse entry : entries) {
      if (entry == null || entry.accountNumber() == null || entry.accountNumber().isBlank()) {
        continue;
      }
      out.add(new SsiDetails(entry.accountNumber(), entry.bankName(), entry.swiftCode()));
    }
    return out;
  }
}
