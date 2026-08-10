package com.sg.domaininterface.port.thirdparty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * The rate used to express a foreign-currency invoice in euros.
 *
 * <p>The rate is asked for as at a date rather than "now", because the euro figure has to stay the
 * same every time the row is read. Re-deriving it from today's rate would make a stored invoice
 * change value overnight.
 */
@FunctionalInterface
public interface CurrencyConverterService {

  /**
   * The mid rate for {@code currency} against the euro on {@code date}.
   *
   * @return the rate, or empty when the referential holds none for that pair and day. Empty is a
   *         normal answer — rates are not published for every currency on every day — and is what
   *         makes the euro amount {@code "NA"} rather than a number nobody can reproduce.
   * @throws ReferentialUnavailableException when the referential could not be reached at all,
   *         which is a different thing from it having no rate.
   */
  Optional<BigDecimal> midRate(LocalDate date, String currency);
}
