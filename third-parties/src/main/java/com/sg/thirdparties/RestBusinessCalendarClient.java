package com.sg.thirdparties;

import com.sg.domaininterface.port.thirdparty.BusinessCalendarService;
import com.sg.domaininterface.port.thirdparty.ReferentialUnavailableException;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * {@link BusinessCalendarService} over the referential's HTTP API.
 *
 * <p><b>The path is assumed.</b> The manual path calls
 * {@code getListOfHolidaysForConsecutiveThreeYears(year, "FRA")}, which fixes the two inputs and
 * the three-year window but not the URL. {@link #PATH} is the line to correct once the real
 * contract is known.
 *
 * <p><b>An empty calendar is a real answer; an unreachable one is not.</b> Serving empty on
 * failure would silently move every re-attachment date onto a public holiday — a date that looks
 * entirely plausible and is wrong, which is worse than not producing one.
 */
public final class RestBusinessCalendarClient implements BusinessCalendarService {

  private static final String REFERENTIAL = "business-calendar";

  private static final String PATH = "/calendar/holidays";

  /** The window the manual path asks for, and the reason the port takes one year rather than a range. */
  private static final int YEARS = 3;

  private static final ParameterizedTypeReference<List<String>> DATE_LIST =
      new ParameterizedTypeReference<>() {};

  private final RestTemplate restTemplate;
  private final ReferentialProperties properties;

  public RestBusinessCalendarClient(RestTemplate restTemplate, ReferentialProperties properties) {
    this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  @Override
  public List<LocalDate> holidays(int year, String countryCode) {
    Objects.requireNonNull(countryCode, "countryCode");

    URI uri = UriComponentsBuilder
        .fromUriString(properties.commonBaseUrl() + PATH)
        .queryParam("fromYear", year)
        .queryParam("years", YEARS)
        .queryParam("countryCode", countryCode)
        .build().encode().toUri();

    try {
      ResponseEntity<List<String>> response =
          restTemplate.exchange(uri, HttpMethod.GET, null, DATE_LIST);

      List<String> body = response.getBody();
      if (body == null) {
        throw new ReferentialUnavailableException(REFERENTIAL,
            "business calendar returned no body for " + countryCode + " from " + year,
            true, null);
      }
      return toDates(body);

    } catch (HttpStatusCodeException e) {
      throw new ReferentialUnavailableException(REFERENTIAL,
          REFERENTIAL + " returned " + e.getStatusCode().value(),
          e.getStatusCode().is5xxServerError(), e);
    } catch (RestClientException e) {
      throw new ReferentialUnavailableException(REFERENTIAL,
          "business calendar unreachable: " + e.getMessage(), true, e);
    }
  }

  /**
   * Parse the returned days, dropping anything unreadable.
   *
   * <p>Dropping rather than raising: one malformed entry among a year's holidays would otherwise
   * take out every registration for that period, and the cost of ignoring it is that a single day
   * is treated as open. Raising would cost the whole calendar.
   */
  private static List<LocalDate> toDates(List<String> raw) {
    List<LocalDate> out = new ArrayList<>(raw.size());
    for (String value : raw) {
      if (value == null || value.isBlank()) {
        continue;
      }
      try {
        out.add(LocalDate.parse(value.trim()));
      } catch (DateTimeParseException e) {
        // Not a date. Nothing sensible to do with it, and it must not stop the rest.
        continue;
      }
    }
    return out;
  }
}
