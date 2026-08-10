package com.sg.domaininterface.port.thirdparty;

import java.time.LocalDate;
import java.util.List;

/**
 * Which days a market is closed, so a date can be walked back to one it is open.
 *
 * <p>Weekends are arithmetic and are not asked for here. Public holidays are not — they move, they
 * differ by country, and a hard-coded list is wrong the first year it is not updated.
 */
@FunctionalInterface
public interface BusinessCalendarService {

  /**
   * Public holidays for {@code year} and the two that follow it.
   *
   * <p>Three years rather than one because the date being adjusted can sit near a year boundary
   * and walk backwards or forwards out of the year it started in. Asking per year would mean a
   * second call at exactly the moment the answer matters most.
   *
   * @param year        the first of the three years
   * @param countryCode ISO-3166 alpha-3, e.g. {@code FRA}
   * @return the holidays, in any order. Empty when the calendar has none — which is a real
   *         answer, and leaves the adjustment resting on weekends alone.
   * @throws ReferentialUnavailableException when the calendar could not be reached.
   */
  List<LocalDate> holidays(int year, String countryCode);
}
