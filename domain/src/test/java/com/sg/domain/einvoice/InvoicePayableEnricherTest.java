package com.sg.domain.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domaininterface.model.einvoice.error.ErrorCode;
import com.sg.domaininterface.model.einvoice.error.MappingError;
import com.sg.domaininterface.model.payableinvoice.InvoicePayable;
import com.sg.domaininterface.model.payableinvoice.InvoicePayableModel;
import com.sg.domaininterface.model.provider.ProviderSetup;
import com.sg.domaininterface.port.out.ProviderSetupLookup;
import com.sg.domaininterface.port.thirdparty.BusinessCalendarService;
import com.sg.domaininterface.port.thirdparty.CurrencyConverterService;
import com.sg.domaininterface.port.thirdparty.ReferentialUnavailableException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The three fields no document can carry.
 *
 * <p>The failure behaviour carries most of the weight. None of these fields decides whether an
 * invoice is valid, so a referential being down must leave a gap and an alert rather than a
 * refusal — and it must leave a gap rather than a plausible-looking default, because a false
 * payment flag and an unread one lead to the same place by different routes.
 */
class InvoicePayableEnricherTest {

  private static final CurrencyConverterService NO_RATES = (date, ccy) -> Optional.empty();
  private static final BusinessCalendarService NO_HOLIDAYS = (year, country) -> List.of();
  private static final ProviderSetupLookup NO_SETUP = (m, f, e) -> Optional.empty();

  private static InvoicePayableModel model() {
    InvoicePayable payable = new InvoicePayable();
    payable.setProviderMnemo("ACME");
    payable.setFeeCategory("CUSTODY");
    payable.setSgEntityMnemonic("SGPAR");
    payable.setSgEntityCode("BDR-G-001");

    InvoicePayableModel m = new InvoicePayableModel();
    m.setInvoicePayable(payable);
    m.setSgEntity("552120222");
    m.setAmount(new BigDecimal("1000.00"));
    m.setCurrency("EUR");
    m.setInvoiceDate(LocalDate.of(2026, 4, 14));
    m.setTradingStartDate(LocalDate.of(2026, 3, 1));
    m.setTradingEndDate(LocalDate.of(2026, 3, 31));
    return m;
  }

  private static InvoicePayableEnricher enricher(CurrencyConverterService rates,
                                                 BusinessCalendarService calendar,
                                                 ProviderSetupLookup setup) {
    return new InvoicePayableEnricher(rates, calendar, setup, Set.of());
  }

  private static boolean has(List<MappingError> errors, ErrorCode code) {
    return errors.stream().anyMatch(e -> e.code() == code);
  }

  // ── amountToEur ───────────────────────────────────────────────────────────

  @Nested
  @DisplayName("the euro amount")
  class EuroAmount {

    @Test
    @DisplayName("a euro invoice is copied across, not round-tripped through a rate")
    void euroIsCopied() {
      InvoicePayableModel m = model();

      // A rate service that would fail if consulted: a euro invoice must not consult one.
      List<MappingError> errors = enricher((date, ccy) -> {
        throw new IllegalStateException("a euro invoice should not ask for a rate");
      }, NO_HOLIDAYS, NO_SETUP).enrich(m);

      assertEquals("1000.00", m.getInvoicePayable().getAmountToEur());
      assertFalse(has(errors, ErrorCode.ENRICHMENT_UNAVAILABLE));
    }

    @Test
    @DisplayName("the currency is matched case-insensitively")
    void euroIgnoresCase() {
      InvoicePayableModel m = model();
      m.setCurrency("eur");

      enricher(NO_RATES, NO_HOLIDAYS, NO_SETUP).enrich(m);

      assertEquals("1000.00", m.getInvoicePayable().getAmountToEur());
    }

    @Test
    @DisplayName("a foreign amount is divided by the rate at the day before trading started")
    void foreignIsConverted() {
      InvoicePayableModel m = model();
      m.setCurrency("USD");

      enricher((date, ccy) -> {
        // The rate is asked for as at a past date, not today: the euro figure is stored, and one
        // that re-derives from the current rate changes value overnight.
        assertEquals(LocalDate.of(2026, 2, 28), date, "the day before trading started");
        assertEquals("USD", ccy);
        return Optional.of(new BigDecimal("1.0850"));
      }, NO_HOLIDAYS, NO_SETUP).enrich(m);

      assertEquals("921.66", m.getInvoicePayable().getAmountToEur());
    }

    @Test
    @DisplayName("rounding is half-down to two places, as on the manual path")
    void roundingMatchesTheManualPath() {
      // 1000 / 3 = 333.333...; half-down to 2dp is 333.33. A different mode here would put
      // e-invoicing rows a cent away from every other row for the same arithmetic.
      InvoicePayableModel m = model();
      m.setCurrency("USD");

      enricher((d, c) -> Optional.of(new BigDecimal("3")), NO_HOLIDAYS, NO_SETUP).enrich(m);

      assertEquals("333.33", m.getInvoicePayable().getAmountToEur());
    }

    @Test
    @DisplayName("no rate published is \"NA\", not a failure")
    void missingRateIsNa() {
      InvoicePayableModel m = model();
      m.setCurrency("USD");

      List<MappingError> errors = enricher(NO_RATES, NO_HOLIDAYS, NO_SETUP).enrich(m);

      assertEquals("NA", m.getInvoicePayable().getAmountToEur());
      assertFalse(has(errors, ErrorCode.ENRICHMENT_UNAVAILABLE),
          "rates are not published for every currency on every day; that is not an outage");
    }

    @Test
    @DisplayName("a zero rate is treated as no rate rather than allowed to divide")
    void zeroRateIsNa() {
      // Dividing by it throws, and an arithmetic failure here would lose an invoice over a
      // referential's typo.
      InvoicePayableModel m = model();
      m.setCurrency("USD");

      enricher((d, c) -> Optional.of(BigDecimal.ZERO), NO_HOLIDAYS, NO_SETUP).enrich(m);

      assertEquals("NA", m.getInvoicePayable().getAmountToEur());
    }

    @Test
    @DisplayName("the invoice date stands in when the document carried no period")
    void invoiceDateIsTheFallback() {
      // An e-invoice with no InvoicePeriod is well-formed UBL. Dropping the euro amount for every
      // one of them would be a bigger gap than rating it a few days out.
      InvoicePayableModel m = model();
      m.setCurrency("USD");
      m.setTradingStartDate(null);

      enricher((date, ccy) -> {
        assertEquals(LocalDate.of(2026, 4, 13), date, "the day before the invoice date");
        return Optional.of(BigDecimal.ONE);
      }, NO_HOLIDAYS, NO_SETUP).enrich(m);

      assertEquals("1000.00", m.getInvoicePayable().getAmountToEur());
    }

    @Test
    @DisplayName("with no date at all the field is left unset")
    void noDateLeavesItUnset() {
      InvoicePayableModel m = model();
      m.setCurrency("USD");
      m.setTradingStartDate(null);
      m.setInvoiceDate(null);

      assertTrue(enricher(NO_RATES, NO_HOLIDAYS, NO_SETUP).enrich(m).isEmpty());
      assertNull(m.getInvoicePayable().getAmountToEur());
    }

    @Test
    @DisplayName("with no currency the field is left unset")
    void noCurrencyLeavesItUnset() {
      InvoicePayableModel m = model();
      m.setCurrency(null);

      enricher(NO_RATES, NO_HOLIDAYS, NO_SETUP).enrich(m);

      assertNull(m.getInvoicePayable().getAmountToEur());
    }

    @Test
    @DisplayName("with no amount there is nothing to convert")
    void noAmountLeavesItUnset() {
      InvoicePayableModel m = model();
      m.setAmount(null);

      enricher(NO_RATES, NO_HOLIDAYS, NO_SETUP).enrich(m);

      assertNull(m.getInvoicePayable().getAmountToEur());
    }

    @Test
    @DisplayName("an unreachable rate service is reported and leaves the field unset")
    void rateOutageIsReported() {
      InvoicePayableModel m = model();
      m.setCurrency("USD");

      List<MappingError> errors = enricher((d, c) -> {
        throw new ReferentialUnavailableException("fx", "down", true, null);
      }, NO_HOLIDAYS, NO_SETUP).enrich(m);

      assertTrue(has(errors, ErrorCode.ENRICHMENT_UNAVAILABLE));
      assertNull(m.getInvoicePayable().getAmountToEur(),
          "\"NA\" would say no rate exists, which is not what an outage means");
      assertNull(ErrorCode.ENRICHMENT_UNAVAILABLE.lifecycleEvent(),
          "an outage on our side must not send the sender's invoice back");
    }

    @Test
    @DisplayName("a rate service that throws something else is still only reported")
    void unexpectedRateFailureIsReported() {
      InvoicePayableModel m = model();
      m.setCurrency("USD");

      List<MappingError> errors = enricher((d, c) -> {
        throw new IllegalStateException("adapter blew up");
      }, NO_HOLIDAYS, NO_SETUP).enrich(m);

      assertTrue(has(errors, ErrorCode.ENRICHMENT_UNAVAILABLE));
      assertTrue(errors.stream().anyMatch(e -> e.detail().contains("threw unexpectedly")));
    }
  }

  // ── reAttachmentDate ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("the re-attachment date")
  class ReAttachment {

    @Test
    @DisplayName("an open month end is itself, not the day before")
    void openMonthEndIsKept() {
      // The manual path's helper is called getDMinusOne and subtracts nothing — it walks back
      // only while the day is closed. 31 March 2026 is a Tuesday. Matching the existing
      // behaviour matters more than matching the name: both producers write this column, and a
      // one-day difference between them reads as a data error.
      InvoicePayableModel m = model();

      enricher(NO_RATES, NO_HOLIDAYS, NO_SETUP).enrich(m);

      assertEquals("2026-03-31", m.getReAttachmentDate());
    }

    @Test
    @DisplayName("a weekend month end walks back to the Friday")
    void weekendWalksBack() {
      InvoicePayableModel m = model();
      // 31 May 2026 is a Sunday; 30 May is the Saturday; 29 May is the Friday.
      m.setTradingEndDate(LocalDate.of(2026, 5, 15));

      enricher(NO_RATES, NO_HOLIDAYS, NO_SETUP).enrich(m);

      assertEquals("2026-05-29", m.getReAttachmentDate());
    }

    @Test
    @DisplayName("a holiday walks back too, and stacks with the weekend")
    void holidayWalksBack() {
      InvoicePayableModel m = model();
      m.setTradingEndDate(LocalDate.of(2026, 3, 15));

      // 31 March is a Tuesday; declare it and the Monday closed, landing on the Friday.
      List<LocalDate> closed = List.of(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 30));

      enricher(NO_RATES, (year, country) -> closed, NO_SETUP).enrich(m);

      assertEquals("2026-03-27", m.getReAttachmentDate());
    }

    @Test
    @DisplayName("the calendar is asked for the right year and country")
    void calendarIsAskedCorrectly() {
      InvoicePayableModel m = model();

      enricher(NO_RATES, (year, country) -> {
        assertEquals(2026, year);
        assertEquals("FRA", country);
        return List.of();
      }, NO_SETUP).enrich(m);
    }

    @Test
    @DisplayName("a period that has not finished is measured from its start")
    void futureEndUsesTheStart() {
      // Otherwise the date lands in a month that has not happened yet.
      InvoicePayableModel m = model();
      m.setTradingStartDate(LocalDate.of(2026, 3, 1));
      m.setTradingEndDate(LocalDate.now().plusYears(1));

      enricher(NO_RATES, NO_HOLIDAYS, NO_SETUP).enrich(m);

      assertEquals("2026-03-31", m.getReAttachmentDate());
    }

    @Test
    @DisplayName("a future end with no start falls back to the end")
    void futureEndWithoutStart() {
      InvoicePayableModel m = model();
      m.setTradingStartDate(null);
      m.setTradingEndDate(LocalDate.of(2027, 3, 15));

      enricher(NO_RATES, NO_HOLIDAYS, NO_SETUP).enrich(m);

      // 31 March 2027 is a Wednesday.
      assertEquals("2027-03-31", m.getReAttachmentDate());
    }

    @Test
    @DisplayName("with no end date the start is used")
    void noEndUsesTheStart() {
      InvoicePayableModel m = model();
      m.setTradingEndDate(null);

      enricher(NO_RATES, NO_HOLIDAYS, NO_SETUP).enrich(m);

      assertEquals("2026-03-31", m.getReAttachmentDate());
    }

    @Test
    @DisplayName("with no trading dates at all the field is left unset")
    void noDatesLeavesItUnset() {
      InvoicePayableModel m = model();
      m.setTradingStartDate(null);
      m.setTradingEndDate(null);

      enricher(NO_RATES, NO_HOLIDAYS, NO_SETUP).enrich(m);

      assertNull(m.getReAttachmentDate());
    }

    @Test
    @DisplayName("an unreachable calendar is reported and leaves the field unset")
    void calendarOutageIsReported() {
      InvoicePayableModel m = model();

      List<MappingError> errors = enricher(NO_RATES, (y, c) -> {
        throw new ReferentialUnavailableException("calendar", "down", true, null);
      }, NO_SETUP).enrich(m);

      assertTrue(has(errors, ErrorCode.ENRICHMENT_UNAVAILABLE));
      // A date produced without the calendar could land on a public holiday and look entirely
      // plausible, which is worse than not producing one.
      assertNull(m.getReAttachmentDate());
    }

    @Test
    @DisplayName("a calendar that throws something else is still only reported")
    void unexpectedCalendarFailureIsReported() {
      InvoicePayableModel m = model();

      List<MappingError> errors = enricher(NO_RATES, (y, c) -> {
        throw new IllegalStateException("adapter blew up");
      }, NO_SETUP).enrich(m);

      assertTrue(errors.stream().anyMatch(e -> e.detail().contains("threw unexpectedly")));
      assertNull(m.getReAttachmentDate());
    }

    @Test
    @DisplayName("a null holiday list is treated as no holidays")
    void nullHolidayListIsTolerated() {
      InvoicePayableModel m = model();

      enricher(NO_RATES, (y, c) -> null, NO_SETUP).enrich(m);

      assertEquals("2026-03-31", m.getReAttachmentDate());
    }
  }

  // ── payment / accounting flags ────────────────────────────────────────────

  @Nested
  @DisplayName("the activation flags")
  class Flags {

    @Test
    @DisplayName("the setup row decides both")
    void setupDecides() {
      InvoicePayableModel m = model();

      enricher(NO_RATES, NO_HOLIDAYS, (mnemo, fee, entity) -> {
        assertEquals("ACME", mnemo);
        assertEquals("CUSTODY", fee, "the fee category NAME, which is what the payable holds");
        assertEquals("SGPAR", entity);
        return Optional.of(new ProviderSetup(true, false));
      }).enrich(m);

      assertEquals(Boolean.TRUE, m.getInvoicePayable().getPaymentFlag());
      assertEquals(Boolean.FALSE, m.getInvoicePayable().getAccountingFlag());
    }

    @Test
    @DisplayName("no setup row means not activated")
    void noSetupIsNotActivated() {
      // Paying a provider nobody has onboarded is the failure worth avoiding, and this is the
      // conclusion the manual path reaches too.
      InvoicePayableModel m = model();

      enricher(NO_RATES, NO_HOLIDAYS, NO_SETUP).enrich(m);

      assertEquals(Boolean.FALSE, m.getInvoicePayable().getPaymentFlag());
      assertEquals(Boolean.FALSE, m.getInvoicePayable().getAccountingFlag());
    }

    @Test
    @DisplayName("a joint-venture entity is activated without a lookup")
    void jointVentureSkipsTheLookup() {
      InvoicePayableModel m = model();

      InvoicePayableEnricher enricher = new InvoicePayableEnricher(
          NO_RATES, NO_HOLIDAYS,
          (mnemo, fee, entity) -> {
            throw new IllegalStateException("a joint venture has no per-provider row to find");
          },
          Set.of("552120222"));

      enricher.enrich(m);

      assertEquals(Boolean.TRUE, m.getInvoicePayable().getPaymentFlag());
      assertEquals(Boolean.TRUE, m.getInvoicePayable().getAccountingFlag());
    }

    @Test
    @DisplayName("the joint-venture list matches the internal code as well as the entity id")
    void jointVentureMatchesEitherIdentity() {
      // The manual path compares one field, but its value is whatever its form supplied. On this
      // path the model carries the customer's SIREN and the payable the golden internal id — the
      // configured list will be in one form or the other.
      InvoicePayableModel m = model();

      new InvoicePayableEnricher(NO_RATES, NO_HOLIDAYS, NO_SETUP, Set.of("BDR-G-001")).enrich(m);

      assertEquals(Boolean.TRUE, m.getInvoicePayable().getPaymentFlag());
    }

    @Test
    @DisplayName("an invoice whose entity never resolved is still enriched")
    void unresolvedEntityDoesNotBlowUp() {
      // Both identities are null on any invoice whose customer SIREN did not resolve, and
      // Set.of().contains(null) throws rather than answering false. Every such invoice used to
      // come back with an enrichment failure that had nothing to do with enrichment.
      InvoicePayableModel m = model();
      m.setSgEntity(null);
      m.getInvoicePayable().setSgEntityCode(null);

      List<MappingError> errors =
          new InvoicePayableEnricher(NO_RATES, NO_HOLIDAYS, NO_SETUP, Set.of("552120222"))
              .enrich(m);

      assertTrue(errors.isEmpty());
      assertEquals(Boolean.FALSE, m.getInvoicePayable().getPaymentFlag());
    }

    @Test
    @DisplayName("an unreadable setup table leaves both flags unset and says so")
    void setupFailureLeavesFlagsUnset() {
      // The manual path swallows this and leaves both false, which is safe but silent: nobody can
      // tell a provider that is not activated from one whose setup could not be read.
      InvoicePayableModel m = model();

      List<MappingError> errors = enricher(NO_RATES, NO_HOLIDAYS, (mn, f, e) -> {
        throw new IllegalStateException("relation t_provider_setup does not exist");
      }).enrich(m);

      assertTrue(has(errors, ErrorCode.ENRICHMENT_UNAVAILABLE));
      assertNull(m.getInvoicePayable().getPaymentFlag());
      assertNull(m.getInvoicePayable().getAccountingFlag());
    }
  }

  // ── Contract ──────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("contract")
  class Contract {

    @Test
    @DisplayName("a null model or a null payload is a no-op, not a failure")
    void nullsAreNoOps() {
      InvoicePayableEnricher e = enricher(NO_RATES, NO_HOLIDAYS, NO_SETUP);

      assertTrue(e.enrich(null).isEmpty());

      InvoicePayableModel m = model();
      m.setInvoicePayable(null);
      assertTrue(e.enrich(m).isEmpty(),
          "mapping already failed upstream; reporting it again would make one problem look "
              + "like two in the alert");
    }

    @Test
    @DisplayName("collaborators are mandatory, the joint-venture list is not")
    void mandatoryCollaborators() {
      assertThrows(NullPointerException.class,
          () -> new InvoicePayableEnricher(null, NO_HOLIDAYS, NO_SETUP, Set.of()));
      assertThrows(NullPointerException.class,
          () -> new InvoicePayableEnricher(NO_RATES, null, NO_SETUP, Set.of()));
      assertThrows(NullPointerException.class,
          () -> new InvoicePayableEnricher(NO_RATES, NO_HOLIDAYS, null, Set.of()));

      // An unconfigured list means no joint ventures, which is the safe reading: every provider
      // gets its setup row checked.
      InvoicePayableModel m = model();
      new InvoicePayableEnricher(NO_RATES, NO_HOLIDAYS, NO_SETUP, null).enrich(m);
      assertEquals(Boolean.FALSE, m.getInvoicePayable().getPaymentFlag());
    }

    @Test
    @DisplayName("every failure is alert-only, so none of them refuses an invoice")
    void failuresNeverRefuse() {
      // The property that makes running this before the rules safe: an outage in any of the three
      // referentials cannot turn into a batch of refusals the senders are asked to act on.
      assertNull(ErrorCode.ENRICHMENT_UNAVAILABLE.lifecycleEvent());
      assertNull(ErrorCode.ENRICHMENT_UNAVAILABLE.reasonCode());
    }
  }
}
