package com.sg.mapper.report;

import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.model.report.Buyer;
import com.sg.domaininterface.model.report.Party;
import com.sg.domaininterface.model.report.PostalAddress;
import com.sg.domaininterface.model.report.QualifiedIdentifier;
import com.sg.domaininterface.model.report.SchemedIdentifier;
import com.sg.domaininterface.model.report.Seller;
import com.sg.domaininterface.model.report.UriUniversalCommunication;
import com.sg.domaininterface.port.out.PartyRegistrationLookup;
import java.util.Optional;

/**
 * Builds the {@link Seller}, {@link Buyer}, and Issuer {@link Party} blocks from SIRENs.
 *
 * <p>Ported from A's {@code ReportPartyMapper} — was a MapStruct {@code @Mapper} interface; now
 * a {@code final} utility class with static methods. Two shape changes vs A:
 *
 * <ul>
 *   <li><b>{@code PartyReferentialClient} replaced by {@link PartyRegistrationLookup}.</b>
 *       The issuer lookup uses the shared referential; {@link PartyRegistrationDetails#name()}
 *       populates the Party name (previously {@code PartyInfo.name}).</li>
 *   <li><b>Interface + default methods → final class + static methods.</b> No bean formation.</li>
 * </ul>
 *
 * <p>Provider is the seller; SG is the buyer — matches the einvoice {@code PartyMapper}
 * convention.
 *
 * <p>Most fields the referential could enrich (name, mnemonic, group, LEI) are <em>not</em>
 * represented in Flux 10's compact Seller/Buyer shape — the schema only carries the
 * registration number and country code. The referential lookup is still issued because the
 * Issuer Party in {@code ReportDocument} (TG-5) needs the declarant's legal name (TT-14).
 */
public final class ReportPartyMapper {

  /** Default scheme for FR SIRENs across Seller / Buyer / Issuer (TT-33-1 / TT-37 / TT-12). */
  public static final String SIREN_SCHEME = "0002";

  /** Default qualifier for VAT TaxRegistrationId values (TT-34-0 / TT-38-0). */
  public static final String VAT_QUALIFIER = "VAT";

  /** Default sender scheme — PA platform (TT-7). */
  public static final String PLATFORM_SCHEME = "0238";

  /** Default sender role — PA platform (TT-10). */
  public static final String PLATFORM_ROLE_CODE = "WK";

  /** Default issuer role when SG is the buyer (TT-15). */
  public static final String BUYER_ROLE_CODE = "BY";

  private ReportPartyMapper() {}

  /** Build the {@link Seller} (TG-12) from the provider's SIREN. */
  public static Seller toSeller(String providerSiren, String providerCountry) {
    if (providerSiren == null || providerSiren.isBlank()) return null;
    return Seller.builder()
        .companyId(SchemedIdentifier.builder()
            .value(providerSiren)
            .schemeId(SIREN_SCHEME)
            .build())
        .taxRegistrationId(QualifiedIdentifier.builder()
            .value(buildVatId(providerSiren))
            .qualifyingId(VAT_QUALIFIER)
            .build())
        .postalAddress(PostalAddress.builder()
            .countryId(providerCountry != null ? providerCountry : "FR")
            .build())
        .build();
  }

  /** Build the {@link Buyer} (TG-14) from SG's SIREN. Mandatory in B2Bi. */
  public static Buyer toBuyer(String sgSiren, String sgCountry) {
    if (sgSiren == null || sgSiren.isBlank()) return null;
    return Buyer.builder()
        .companyId(SchemedIdentifier.builder()
            .value(sgSiren)
            .schemeId(SIREN_SCHEME)
            .build())
        .taxRegistrationId(QualifiedIdentifier.builder()
            .value(buildVatId(sgSiren))
            .qualifyingId(VAT_QUALIFIER)
            .build())
        .postalAddress(PostalAddress.builder()
            .countryId(sgCountry != null ? sgCountry : "FR")
            .build())
        .build();
  }

  /**
   * Build the Issuer {@link Party} (TG-5) for {@code ReportDocument}. The declarant is SG.
   * Legal name comes from the {@link PartyRegistrationLookup}; SIREN is what we have on
   * {@code InvoicePayableModel.sgEntity}.
   */
  public static Party toIssuer(String sgSiren, PartyRegistrationLookup lookup, String uriId) {
    if (sgSiren == null || sgSiren.isBlank()) return null;
    Optional<PartyRegistrationDetails> info =
        lookup == null ? Optional.empty() : lookup.findBySiren(sgSiren);
    return Party.builder()
        .id(SchemedIdentifier.builder()
            .value(sgSiren)
            .schemeId(SIREN_SCHEME)
            .build())
        .name(info.map(PartyRegistrationDetails::name).orElse(null))
        .roleCode(BUYER_ROLE_CODE)
        .uriUniversalCommunication(uriId != null
            ? UriUniversalCommunication.builder().uriId(uriId).build()
            : null)
        .build();
  }

  /** Build the Sender {@link Party} (TG-3) from the PA platform config. */
  public static Party toSender(ReportFlowConfig config) {
    if (config == null) return null;
    return Party.builder()
        .id(SchemedIdentifier.builder()
            .value(config.getPlatformMatricule())
            .schemeId(PLATFORM_SCHEME)
            .build())
        .name(config.getPlatformName())
        .roleCode(PLATFORM_ROLE_CODE)
        .uriUniversalCommunication(config.getPlatformUriId() != null
            ? UriUniversalCommunication.builder().uriId(config.getPlatformUriId()).build()
            : null)
        .build();
  }

  /**
   * Synthesise a VAT identifier from a SIREN. The real VAT id is {@code FR<key><SIREN>} where
   * {@code key} is a 2-digit Luhn-style check; we don't have the real key on the source model,
   * so we prepend {@code FR00} as a placeholder.
   */
  static String buildVatId(String siren) {
    return "FR00" + siren;
  }
}
