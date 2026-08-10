package com.sg.domain.einvoice;

import com.sg.domaininterface.model.einvoice.error.ErrorCode;
import com.sg.domaininterface.model.einvoice.error.MappingError;
import com.sg.domaininterface.model.payableinvoice.InvoicePayable;
import com.sg.domaininterface.model.payableinvoice.InvoicePayableModel;
import com.sg.domaininterface.model.provider.ProviderSetup;
import com.sg.domaininterface.port.out.InvoiceEnrichmentPort;
import com.sg.domaininterface.port.out.ProviderSetupLookup;
import com.sg.domaininterface.port.thirdparty.BusinessCalendarService;
import com.sg.domaininterface.port.thirdparty.CurrencyConverterService;
import com.sg.domaininterface.port.thirdparty.ReferentialUnavailableException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The three fields that cannot be worked out from the document alone.
 *
 * <p>{@code amountToEur}, {@code reAttachmentDate} and the payment/accounting flags each need
 * something outside the invoice — a rate, a calendar, an onboarding record. The manual
 * registration path computes all three; the e-invoicing path left them blank, which meant a
 * foreign-currency invoice had no euro figure to report on, no re-attachment date to schedule
 * against, and flags that read as "do not pay, do not book" without anyone having decided that.
 *
 * <p><b>Why this is not in the mapper.</b> The mapper turns one document into another and needs no
 * collaborators to do it. Three referentials would end that: it would no longer be reproducible
 * from its input, and a rate outage would surface as a mapping failure. This is enrichment — it
 * runs after mapping, on the model the mapper produced.
 *
 * <p><b>Nothing here refuses an invoice.</b> None of these fields decides whether the invoice is
 * valid; they decide what happens to it afterwards. Every failure is recorded as an alert-only
 * {@link ErrorCode#ENRICHMENT_UNAVAILABLE} and the field is left unset, so an outage in the rate
 * service cannot turn into a batch of refusals the senders are asked to act on.
 *
 * <p><b>Fields are left unset rather than defaulted.</b> Null says "this could not be worked out",
 * which is the truth and is visible. A zero euro amount or a silently-false payment flag says
 * something was decided, and reads exactly like a decision someone made.
 */
public final class InvoicePayableEnricher implements InvoiceEnrichmentPort {

  private static final System.Logger LOG =
      System.getLogger(InvoicePayableEnricher.class.getName());

  private static final String EUR = "EUR";

  /** What {@code amountToEur} holds when no rate exists for the pair and day. */
  private static final String NO_RATE = "NA";

  /** The calendar the re-attachment date is walked against. */
  private static final String CALENDAR_COUNTRY = "FRA";

  private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  /** Scale and rounding of the euro amount, matching the manual path exactly. */
  private static final int EURO_SCALE = 2;

  private final CurrencyConverterService rates;
  private final BusinessCalendarService calendar;
  private final ProviderSetupLookup providerSetup;

  /**
   * Entities whose invoices are paid and booked without consulting the setup table.
   *
   * <p>Joint ventures, where the arrangement is agreed at the entity rather than per provider.
   * Configured rather than hard-coded because the list changes when a venture is formed or wound
   * up, and neither event should need a release.
   */
  private final Set<String> jointVentureEntities;

  public InvoicePayableEnricher(CurrencyConverterService rates,
                                BusinessCalendarService calendar,
                                ProviderSetupLookup providerSetup,
                                Set<String> jointVentureEntities) {
    this.rates = Objects.requireNonNull(rates, "rates");
    this.calendar = Objects.requireNonNull(calendar, "calendar");
    this.providerSetup = Objects.requireNonNull(providerSetup, "providerSetup");
    this.jointVentureEntities = jointVentureEntities == null
        ? Set.of()
        : Set.copyOf(jointVentureEntities);
  }

  /**
   * Fill what the document could not supply.
   *
   * @return one alert-only error per referential that could not be consulted; empty when
   *         everything resolved, or when there was nothing to resolve.
   */
  @Override
  public List<MappingError> enrich(InvoicePayableModel model) {
    List<MappingError> errors = new ArrayList<>();
    if (model == null || model.getInvoicePayable() == null) {
      // Mapping failed upstream and there is nothing to enrich. Reporting that again here would
      // make one failure look like two in the alert.
      return errors;
    }
    applyEuroAmount(model, errors);
    applyReAttachmentDate(model, errors);
    applyActivationFlags(model, errors);
    return errors;
  }

  // ── amountToEur ───────────────────────────────────────────────────────────

  /**
   * The invoice total expressed in euros.
   *
   * <p>Rated as at the day before trading started, not today: the euro figure is stored, and a
   * stored figure that re-derives from the current rate changes value overnight. An invoice
   * already in euros is copied across rather than round-tripped through a rate of one.
   */
  private void applyEuroAmount(InvoicePayableModel model, List<MappingError> errors) {
    InvoicePayable payable = model.getInvoicePayable();
    BigDecimal amount = model.getAmount();
    if (amount == null) {
      return;
    }
    if (EUR.equalsIgnoreCase(model.getCurrency())) {
      payable.setAmountToEur(amount.toString());
      return;
    }

    LocalDate ratedOn = rateDate(model);
    if (ratedOn == null || model.getCurrency() == null) {
      // No date to rate against, or no currency to rate. Both mean the document did not carry
      // enough to convert, which other rules will already be reporting on.
      return;
    }

    try {
      Optional<BigDecimal> rate = rates.midRate(ratedOn, model.getCurrency());
      payable.setAmountToEur(convert(amount, rate.orElse(null)));
    } catch (ReferentialUnavailableException ex) {
      errors.add(MappingError.of(ErrorCode.ENRICHMENT_UNAVAILABLE,
          "no " + model.getCurrency() + " rate for " + ratedOn + ": " + ex.getMessage(), ex));
    } catch (RuntimeException ex) {
      // The port is contracted to raise ReferentialUnavailableException; an adapter is still code.
      errors.add(MappingError.of(ErrorCode.ENRICHMENT_UNAVAILABLE,
          "rate service threw unexpectedly: " + ex.getMessage(), ex));
    }
  }

  /**
   * {@code "NA"} when there is no usable rate, matching the manual path.
   *
   * <p>A zero rate is treated as no rate rather than allowed to divide: it would throw, and an
   * arithmetic failure here would lose an invoice over a referential typo.
   */
  private static String convert(BigDecimal amount, BigDecimal rate) {
    if (rate == null || rate.signum() == 0) {
      return NO_RATE;
    }
    return amount.divide(rate, EURO_SCALE, RoundingMode.HALF_DOWN).toString();
  }

  /**
   * The day the rate is taken from: the day before trading started.
   *
   * <p>Falls back to the day before the invoice date when the document carried no period. The
   * manual path has no such fallback because its form requires the dates; an e-invoice without an
   * {@code InvoicePeriod} is well-formed UBL, and dropping the euro amount for every one of them
   * would be a bigger gap than rating it a few days out.
   */
  private static LocalDate rateDate(InvoicePayableModel model) {
    LocalDate basis = model.getTradingStartDate() != null
        ? model.getTradingStartDate()
        : model.getInvoiceDate();
    return basis == null ? null : basis.minusDays(1);
  }

  // ── reAttachmentDate ──────────────────────────────────────────────────────

  /**
   * The last open day of the trading month.
   *
   * <p>Reproduces the manual path's arithmetic, including the part its name misdescribes: the
   * helper is called {@code getDMinusOne} but subtracts nothing — it takes the month end and walks
   * back only while that day is closed. A month ending on an open Tuesday yields that Tuesday.
   * Matching the existing behaviour matters more than matching the name, since both producers
   * write the same column and a one-day difference between them would be read as a data error.
   *
   * <p>A trading period that has not finished yet is measured from its start instead, so the date
   * lands in a month that has actually happened.
   */
  private void applyReAttachmentDate(InvoicePayableModel model, List<MappingError> errors) {
    LocalDate basis = reAttachmentBasis(model);
    if (basis == null) {
      return;
    }
    LocalDate monthEnd = basis.with(TemporalAdjusters.lastDayOfMonth());
    try {
      List<LocalDate> holidays = calendar.holidays(monthEnd.getYear(), CALENDAR_COUNTRY);
      model.setReAttachmentDate(ISO_DATE.format(lastOpenDayOnOrBefore(monthEnd, holidays)));
    } catch (ReferentialUnavailableException ex) {
      errors.add(MappingError.of(ErrorCode.ENRICHMENT_UNAVAILABLE,
          "no business calendar for " + monthEnd.getYear() + ": " + ex.getMessage(), ex));
    } catch (RuntimeException ex) {
      errors.add(MappingError.of(ErrorCode.ENRICHMENT_UNAVAILABLE,
          "business calendar threw unexpectedly: " + ex.getMessage(), ex));
    }
  }

  private static LocalDate reAttachmentBasis(InvoicePayableModel model) {
    LocalDate end = model.getTradingEndDate();
    LocalDate start = model.getTradingStartDate();
    if (end == null) {
      return start;
    }
    if (end.isAfter(LocalDate.now()) && start != null) {
      return start;
    }
    return end;
  }

  /** Walk back off weekends and holidays. A null in the holiday list is simply not a match. */
  private static LocalDate lastOpenDayOnOrBefore(LocalDate from, List<LocalDate> holidays) {
    Set<LocalDate> closed = new HashSet<>(holidays == null ? List.of() : holidays);
    LocalDate day = from;
    while (day.getDayOfWeek() == DayOfWeek.SATURDAY
        || day.getDayOfWeek() == DayOfWeek.SUNDAY
        || closed.contains(day)) {
      day = day.minusDays(1);
    }
    return day;
  }

  // ── paymentFlag / accountingFlag ──────────────────────────────────────────

  /**
   * Whether this invoice may be paid, and whether it may be booked.
   *
   * <p>Joint-venture entities are activated for both without a lookup — the arrangement is agreed
   * at the entity, and there is no per-provider row to find.
   *
   * <p>Everything else comes from the provider's setup for this fee category at this entity. No
   * setup row means not activated, which is the same conclusion the manual path reaches and the
   * safe direction: paying a provider nobody has onboarded is the failure worth avoiding.
   */
  private void applyActivationFlags(InvoicePayableModel model, List<MappingError> errors) {
    InvoicePayable payable = model.getInvoicePayable();
    if (isJointVenture(model, payable)) {
      payable.setPaymentFlag(true);
      payable.setAccountingFlag(true);
      return;
    }

    try {
      Optional<ProviderSetup> setup = providerSetup.find(
          payable.getProviderMnemo(), payable.getFeeCategory(), payable.getSgEntityMnemonic());
      payable.setPaymentFlag(setup.map(ProviderSetup::paymentActivation).orElse(false));
      payable.setAccountingFlag(setup.map(ProviderSetup::accountingActivation).orElse(false));
    } catch (RuntimeException ex) {
      // The manual path swallows this and leaves both false, which is safe but silent — nobody
      // can tell a provider that is not activated from one whose setup could not be read. Both
      // flags stay unset here and the reason is recorded, so the difference survives.
      LOG.log(System.Logger.Level.WARNING,
          "provider setup lookup failed for " + payable.getProviderMnemo() + ": "
              + ex.getMessage(), ex);
      errors.add(MappingError.of(ErrorCode.ENRICHMENT_UNAVAILABLE,
          "provider setup unavailable for mnemonic '" + payable.getProviderMnemo()
              + "': " + ex.getMessage(), ex));
    }
  }

  /**
   * Whether the SG side of this invoice is a joint venture.
   *
   * <p>Checked against both the entity identifier on the model and the internal code resolved onto
   * the payable. The manual path compares one field, but its value is whatever its form supplied,
   * and on this path the model carries the customer's SIREN while the payable carries the golden
   * internal id — the configured list will be in one form or the other, and testing both is
   * cheaper than being wrong about which.
   */
  private boolean isJointVenture(InvoicePayableModel model, InvoicePayable payable) {
    // Both identities are absent on any invoice whose customer SIREN did not resolve, and
    // Set.of() rejects a null argument to contains() rather than answering false — so the null
    // check is load-bearing, not defensive.
    return isListed(model.getSgEntity()) || isListed(payable.getSgEntityCode());
  }

  private boolean isListed(String identity) {
    return identity != null && jointVentureEntities.contains(identity);
  }
}
