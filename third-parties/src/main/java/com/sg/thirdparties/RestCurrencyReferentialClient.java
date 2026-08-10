package com.sg.thirdparties;

import com.sg.domaininterface.port.thirdparty.CurrencyConverterService;
import com.sg.domaininterface.port.thirdparty.ReferentialUnavailableException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * {@link CurrencyConverterService} over the referential's HTTP API.
 *
 * <p><b>The path and response shape are assumed.</b> The manual path calls
 * {@code referentialServiceApi.getAmountInEuro(date, currency)} and reads {@code getMidValue()}
 * off the result, which fixes the two inputs and the one field that matters but not the URL. This
 * follows the conventions of the clients already in this module; the two constants below are what
 * change when the real contract is known.
 *
 * <p><b>No rate and no service are different answers.</b> An empty result makes the euro amount
 * {@code "NA"} — an honest record that no rate was published. An outage raises, so it can be
 * reported as an outage instead of being written into a row as though it were a fact about the
 * currency.
 */
public final class RestCurrencyReferentialClient implements CurrencyConverterService {

  private static final String REFERENTIAL = "currency-converter";

  private static final String PATH = "/currency-converter/mid-rate";

  private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

  /**
   * The response, named for the field the manual path reads.
   *
   * <p>Its own type rather than a map: the payload carries more than this, and a map would accept
   * a response that had changed shape and quietly hand back nothing.
   */
  public record CurrencyConverterResponse(String currency, String midValue) {}

  private final RestTemplate restTemplate;
  private final ReferentialProperties properties;

  public RestCurrencyReferentialClient(RestTemplate restTemplate,
                                       ReferentialProperties properties) {
    this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  @Override
  public Optional<BigDecimal> midRate(LocalDate date, String currency) {
    Objects.requireNonNull(date, "date");
    Objects.requireNonNull(currency, "currency");

    URI uri = UriComponentsBuilder
        .fromUriString(properties.commonBaseUrl() + PATH)
        .queryParam("date", ISO_DATE.format(date))
        .queryParam("currency", currency)
        .build().encode().toUri();

    try {
      ResponseEntity<CurrencyConverterResponse> response =
          restTemplate.exchange(uri, HttpMethod.GET, null, CurrencyConverterResponse.class);
      return toRate(response.getBody());

    } catch (HttpStatusCodeException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        // No rate published for this pair and day. Common, and not a fault.
        return Optional.empty();
      }
      throw new ReferentialUnavailableException(REFERENTIAL,
          REFERENTIAL + " returned " + e.getStatusCode().value() + " for " + currency
              + " on " + date,
          e.getStatusCode().is5xxServerError(), e);
    } catch (RestClientException e) {
      throw new ReferentialUnavailableException(REFERENTIAL,
          "currency referential unreachable: " + e.getMessage(), true, e);
    }
  }

  /**
   * The mid value as a number.
   *
   * <p>Read as a string and parsed here because the manual path treats it as one
   * ({@code new BigDecimal(getMidValue())}), and because a rate deserialised straight into a
   * {@code double} would lose digits before anything could divide by it.
   *
   * <p>An unparseable value is empty rather than an exception: it means no usable rate, which is
   * the same outcome for the invoice as no rate at all, and raising would refuse a registration
   * over a referential's typo.
   */
  private static Optional<BigDecimal> toRate(CurrencyConverterResponse body) {
    if (body == null || body.midValue() == null || body.midValue().isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(new BigDecimal(body.midValue().trim()));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }
}
