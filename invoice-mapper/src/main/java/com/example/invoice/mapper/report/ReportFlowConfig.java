package com.example.invoice.mapper.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Per-environment configuration for the {@link com.example.invoice.mapper.report.model.ReportModel}
 * build pipeline. Holds the values that don't sit on {@code InvoicePayable} but are still needed
 * to compose a valid Flux 10 transmission (PA platform identity, default business process,
 * default transmission type).
 *
 * <p>Vendored verbatim from A's {@code com.sg.domaininterface.mapper.report.ReportFlowConfig}.
 * Callers construct via the Lombok builder; there is no MapStruct bean formation.
 *
 * <p>Per-spec defaults baked into the mapper sites (not held here):
 * <ul>
 *   <li>Sender {@code @schemeId} is always {@code "0238"} (PA platform).</li>
 *   <li>Sender {@code RoleCode} is always {@code "WK"}.</li>
 *   <li>Issuer {@code @schemeId} is always {@code "0002"} (SIREN).</li>
 *   <li>Issuer {@code RoleCode} is {@code "BY"} for SG-as-buyer flows.</li>
 *   <li>Tax {@code @qualifyingId} is always {@code "VAT"}.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class ReportFlowConfig {

  /** 4-char PA platform matricule (Sender/Id value, TT-8). Placeholder until prod bean lands. */
  @Builder.Default private String platformMatricule = "PA01";

  /** Legal name of the PA platform (Sender/Name, TT-9). Placeholder. */
  @Builder.Default private String platformName = "PLACEHOLDER PA PLATFORM";

  /** Optional CEF URI for the PA (Sender/URIUniversalCommunication/URIID, TT-11). */
  private String platformUriId;

  /** Optional CEF URI for the issuer/declarant (Issuer/URIUniversalCommunication/URIID, TT-16). */
  private String issuerUriId;

  /** TT-28 default business-process code. {@code S1} = services, {@code B1} = goods, etc. */
  @Builder.Default private String defaultBusinessProcessId = "S1";

  /** TT-29 default profile URN. */
  @Builder.Default private String defaultBusinessProcessTypeId = "urn.cpro.gouv.fr:1p0:ereporting";

  /** TT-4 default — {@code IN} (initial) or {@code RE} (rectificative). */
  @Builder.Default private String defaultTransmissionTypeCode = "IN";
}
