package com.example.invoice.mapper.einvoice;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Date conversions between the two models.
 *
 * <p>Ported from A's {@code DateMapper} — was a MapStruct {@code @Mapper} interface with default
 * methods; now a {@code final} utility class with static methods. The stateless nature is
 * unchanged; MapStruct only ever gave callers a Spring bean around methods that never touched
 * instance state.
 *
 * <p>Both the einvoice UBL model and {@link
 * com.example.invoice.service.domain.model.payableinvoice.InvoicePayable} use {@link LocalDate}
 * for most date fields, so the heavy lifting here is tolerant parsing of stringified dates that
 * show up in JSON payload columns (ISO-8601).
 */
public final class DateMapper {

  private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

  private DateMapper() {}

  public static LocalDate parse(String iso) {
    if (iso == null || iso.isBlank()) return null;
    try {
      return LocalDate.parse(iso, ISO);
    } catch (DateTimeParseException ex) {
      return null;
    }
  }

  public static String format(LocalDate date) {
    return date == null ? null : date.format(ISO);
  }
}
