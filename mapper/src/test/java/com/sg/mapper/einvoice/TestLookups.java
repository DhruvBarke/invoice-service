package com.sg.mapper.einvoice;

import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.port.in.PartyRegistrationLookup;
import java.util.List;
import java.util.Optional;

/** Party-lookup doubles shared by the mapper tests. */
final class TestLookups {

  static final PartyRegistrationDetails ACME = new PartyRegistrationDetails(
      "ELEM-9", "Lyon branch", "LYON", "TP-1", "Acme SA", "ACME",
      "BDR-G-001", "Acme SA", "ACME", "123456789", "12345678900012", List.of());

  private TestLookups() {}

  static PartyRegistrationLookup alwaysFinds() {
    return of(ACME);
  }

  static PartyRegistrationLookup findsNothing() {
    return of(null);
  }

  static PartyRegistrationLookup of(PartyRegistrationDetails result) {
    return new PartyRegistrationLookup() {
      @Override public Optional<PartyRegistrationDetails> findByBdrId(String b) {
        return Optional.ofNullable(result);
      }
      @Override public Optional<PartyRegistrationDetails> findBySiren(String s) {
        return Optional.ofNullable(result);
      }
      @Override public Optional<PartyRegistrationDetails> findBySiret(String s) {
        return Optional.ofNullable(result);
      }
      @Override public List<PartyRegistrationDetails> findAllBySiret(String s) {
        return result == null ? List.of() : List.of(result);
      }
    };
  }
}
