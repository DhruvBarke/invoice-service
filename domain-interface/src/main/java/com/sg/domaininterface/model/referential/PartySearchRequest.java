package com.sg.domaininterface.model.referential;

import com.sg.domaininterface.model.party.RegistrationType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The criteria for a party-registration lookup.
 *
 * <p>A single object rather than a method per criterion, because the referential answers one
 * endpoint whose query string varies by which criteria were supplied. Every populated field
 * becomes one query parameter; blank and null fields are left out entirely rather than sent as
 * empty values, which the referential would treat as "match the empty string" instead of "do not
 * filter on this".
 *
 * <p>Adding a criterion is adding a field here and a line in {@link #toQueryParameters()}. It
 * does not change the port signature, so no adapter or caller has to be touched for one.
 */
public record PartySearchRequest(
    String siren,
    String siret,
    String goldenBdrId,
    String mnemonic,
    String name,
    String countryCode) {

  public PartySearchRequest {
    if (siren == null && siret == null && goldenBdrId == null
        && mnemonic == null && name == null && countryCode == null) {
      // An unfiltered request would ask the referential for every party it holds. That is not a
      // lookup, and the failure is much cheaper here than as a timeout downstream.
      throw new IllegalArgumentException(
          "a party search needs at least one criterion; all fields were null");
    }
  }

  /** The common case: the French company identifier. */
  public static PartySearchRequest bySiren(String siren) {
    return new PartySearchRequest(siren, null, null, null, null, null);
  }

  /** An establishment rather than the company — a SIREN plus its five-digit NIC. */
  public static PartySearchRequest bySiret(String siret) {
    return new PartySearchRequest(null, siret, null, null, null, null);
  }

  /**
   * By registration id, with the type deciding which criterion it becomes.
   *
   * <p>Here rather than at the call sites: SIREN and SIRET are different columns upstream, and a
   * caller that put a SIRET in the siren parameter would get a confident empty answer rather
   * than an error.
   */
  public static PartySearchRequest byRegistration(String registrationId, RegistrationType type) {
    Objects.requireNonNull(type, "type");
    return type == RegistrationType.SIRET ? bySiret(registrationId) : bySiren(registrationId);
  }

  /** SG's own identifier for a party, when the caller already holds one. */
  public static PartySearchRequest byGoldenBdrId(String goldenBdrId) {
    return new PartySearchRequest(null, null, goldenBdrId, null, null, null);
  }

  /**
   * The populated criteria, in a stable order, ready to become a query string.
   *
   * <p>Ordered because the URL is a cache key upstream: two requests with the same criteria in a
   * different order would otherwise miss each other's cached response for no reason.
   *
   * <p>Values are returned raw. Encoding belongs to whatever builds the URI — doing it here
   * would mean either double-encoding or hoping the caller knows not to.
   */
  public Map<String, String> toQueryParameters() {
    Map<String, String> params = new LinkedHashMap<>();
    put(params, "siren", siren);
    put(params, "siret", siret);
    put(params, "goldenBdrId", goldenBdrId);
    put(params, "mnemonic", mnemonic);
    put(params, "name", name);
    put(params, "countryCode", countryCode);
    return params;
  }

  private static void put(Map<String, String> params, String key, String value) {
    if (value != null && !value.isBlank()) {
      params.put(key, value.trim());
    }
  }

  /** A short description for log lines and error messages, without dumping the whole record. */
  public String describe() {
    return toQueryParameters().entrySet().stream()
        .map(e -> e.getKey() + "=" + e.getValue())
        .reduce((a, b) -> a + "&" + b)
        .orElse("<no criteria>");
  }

  @Override
  public String toString() {
    return "PartySearchRequest[" + describe() + "]";
  }

  /** Two requests asking the same thing are the same request, whatever built them. */
  @Override
  public boolean equals(Object o) {
    return o instanceof PartySearchRequest other
        && Objects.equals(toQueryParameters(), other.toQueryParameters());
  }

  @Override
  public int hashCode() {
    return toQueryParameters().hashCode();
  }
}
