package com.sg.mapper.einvoice;

import static com.sg.mapper.einvoice.Constant.*;

import com.sg.domaininterface.model.invoice.AccountingCustomerParty;
import com.sg.domaininterface.model.invoice.AccountingSupplierParty;
import com.sg.domaininterface.model.invoice.CodedValue;
import com.sg.domaininterface.model.invoice.Country;
import com.sg.domaininterface.model.invoice.Party;
import com.sg.domaininterface.model.invoice.PartyLegalEntity;
import com.sg.domaininterface.model.invoice.PartyTaxScheme;
import com.sg.domaininterface.model.invoice.PostalAddress;
import com.sg.domaininterface.model.invoice.SchemeID;
import com.sg.domaininterface.model.invoice.TaxSchemeRef;
import java.util.List;

/**
 * Mappers for the supplier/customer party blocks.
 *
 * <p>Ported from A's {@code PartyMapper} — was a MapStruct {@code @Mapper} interface with
 * default methods; now a {@code final} utility class with static methods. Two shape changes vs
 * A on the type side:
 *
 * <ul>
 *   <li>{@code PartyTaxScheme.companyId} and {@code PartyLegalEntity.companyId} are typed as
 *       {@link SchemeID} in the in-repo Invoice model (feesone typed them as
 *       {@link CodedValue}). Same {@code setValue}/{@code setSchemeID} surface, so the mapper
 *       body just swaps the constructor.</li>
 *   <li>{@code TaxSchemeRef} replaces feesone {@code TaxScheme} — the {@code setIdValue} calls
 *       on A's mapper are dropped because {@code TaxSchemeRef} exposes {@code idValue} as a
 *       computed getter over {@code id}.</li>
 * </ul>
 *
 * <p>Convention (confirmed 2026-07-01): the <strong>provider</strong> is the UBL accounting
 * supplier and <strong>SG</strong> is the UBL accounting customer. This matches the PDP-bound
 * flow where SG is the invoice recipient.
 *
 * <p>Outbound conventions (Payable → einvoice):
 * <ul>
 *   <li>Supplier {@code partyLegalEntity.companyId.value} = {@code providerId} (also reused as
 *       {@code partyTaxScheme[0].companyId.value}).</li>
 *   <li>Customer {@code partyLegalEntity.companyId.value} = {@code sgEntity}.</li>
 *   <li>{@code registrationName} = the {@code *Name} field from the payable JSON
 *       ({@code providerName} for supplier, {@code sgEntityName} for customer).</li>
 *   <li>Both parties get a placeholder Paris/75009/FR postal address since the payable model
 *       does not carry one — EN16931 mandates an address.</li>
 * </ul>
 */
public final class PartyMapper {

  private PartyMapper() {}

  // ── outbound ─────────────────────────────────────────────────────────────

  /** Provider becomes the UBL supplier. */
  public static AccountingSupplierParty toSupplier(String providerId, String providerName) {
    AccountingSupplierParty wrap = new AccountingSupplierParty();
    wrap.setParty(buildParty(providerId, providerName));
    return wrap;
  }

  /** SG becomes the UBL customer. */
  public static AccountingCustomerParty toCustomer(String sgEntity, String sgEntityName) {
    AccountingCustomerParty wrap = new AccountingCustomerParty();
    wrap.setParty(buildParty(sgEntity, sgEntityName));
    return wrap;
  }

  static Party buildParty(String companyId, String registrationName) {
    Party party = new Party();

    if (companyId != null) {
      PartyTaxScheme pts = new PartyTaxScheme();
      SchemeID ptsCompanyId = new SchemeID();
      ptsCompanyId.setValue(companyId);
      pts.setCompanyId(ptsCompanyId);

      SchemeID schemeId = new SchemeID();
      schemeId.setValue(DEFAULT_VAT_SCHEME);
      TaxSchemeRef scheme = new TaxSchemeRef();
      scheme.setId(schemeId);
      pts.setTaxScheme(scheme);

      party.setPartyTaxScheme(List.of(pts));
    }

    PartyLegalEntity ple = new PartyLegalEntity();
    if (registrationName != null) {
      ple.setRegistrationName(registrationName);
    }
    if (companyId != null) {
      SchemeID pleCompanyId = new SchemeID();
      pleCompanyId.setValue(companyId);
      pleCompanyId.setSchemeID("0002");
      ple.setCompanyId(pleCompanyId);
    }
    SchemeID pleSchemeId = new SchemeID();
    pleSchemeId.setValue(DEFAULT_VAT_SCHEME);
    TaxSchemeRef pleScheme = new TaxSchemeRef();
    pleScheme.setId(pleSchemeId);
    ple.setTaxScheme(pleScheme);
    party.setPartyLegalEntity(ple);

    // Placeholder address — the payable model has no postal address but EN16931 requires one.
    PostalAddress addr = new PostalAddress();
    addr.setCityName(DEFAULT_CITY);
    addr.setPostalZone(DEFAULT_POSTAL_ZONE);
    Country country = new Country();
    CodedValue cc = new CodedValue();
    cc.setValue(DEFAULT_COUNTRY);
    country.setIdentificationCode(cc);
    addr.setCountry(country);
    party.setPostalAddress(addr);

    return party;
  }

  // ── inbound ──────────────────────────────────────────────────────────────

  /** SIREN of the provider (UBL supplier). */
  public static String extractProviderRegistrationNumber(AccountingSupplierParty supplier) {
    if (supplier == null || supplier.getParty() == null) return null;
    PartyLegalEntity ple = supplier.getParty().getPartyLegalEntity();
    if (ple == null || ple.getCompanyId() == null) return null;
    return ple.getCompanyId().getValue();
  }

  /** SIREN of the SG legal entity (UBL customer). */
  public static String extractSgEntityRegistrationNumber(AccountingCustomerParty customer) {
    if (customer == null || customer.getParty() == null) return null;
    PartyLegalEntity ple = customer.getParty().getPartyLegalEntity();
    if (ple == null || ple.getCompanyId() == null) return null;
    return ple.getCompanyId().getValue();
  }
}
