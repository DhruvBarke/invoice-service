package com.example.invoice.mapper.report;

import com.example.invoice.service.domain.model.payableinvoice.InvoicePayableModel;
import com.example.invoice.service.domain.model.report.IssueDateTime;
import com.example.invoice.service.domain.model.report.ReportDocument;
import com.example.invoice.service.domain.port.in.PartyRegistrationLookup;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Builds the {@link ReportDocument} header (TB-1) — the identity metadata that fronts every
 * Flux 10 transmission.
 *
 * <p>Ported from A's {@code ReportDocumentMapper} — was a MapStruct-generated {@code abstract
 * class}; now a {@code final} utility class with static methods. Two shape changes vs A:
 *
 * <ul>
 *   <li><b>{@code PartyReferentialClient} replaced by {@link PartyRegistrationLookup}.</b></li>
 *   <li><b>{@code Flux10DateSerde.DATE_TIME_FORMAT} inlined as {@link #DATE_TIME_FORMAT}.</b>
 *       {@link com.example.invoice.service.domain.model.report.package-info} explains why
 *       {@code Flux10DateSerde} itself was omitted from the vendored model.</li>
 * </ul>
 *
 * <p>Transmission {@code Id} is composed as
 * {@code <sgSiren>_<invoiceReference>_<yyyyMMddHHmmss>} — unique per period per declarant per
 * invoice, sufficient for the per-invoice mapper scope. Aggregated transmissions use a
 * different scheme, driven by the registration service.
 */
public final class ReportDocumentMapper {

  /** {@code AAAAMMJJHHMMSS} format used in transmission ids (formerly {@code Flux10DateSerde}). */
  static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  private ReportDocumentMapper() {}

  public static ReportDocument toReportDocument(
      InvoicePayableModel model,
      ReportFlowConfig config,
      PartyRegistrationLookup lookup,
      LocalDateTime issueDateTime) {

    if (model == null) throw new ReportMappingException("InvoicePayableModel is null");
    if (model.getSgEntity() == null || model.getSgEntity().isBlank()) {
      throw new ReportMappingException("InvoicePayableModel.sgEntity is required for report Issuer");
    }

    LocalDateTime now = issueDateTime != null ? issueDateTime : LocalDateTime.now();

    return ReportDocument.builder()
        .id(composeTransmissionId(model, now))
        .issueDateTime(IssueDateTime.builder().dateTimeString(now).build())
        .typeCode(config != null ? config.getDefaultTransmissionTypeCode() : "IN")
        .sender(ReportPartyMapper.toSender(config))
        .issuer(ReportPartyMapper.toIssuer(
            model.getSgEntity(),
            lookup,
            config != null ? config.getIssuerUriId() : null))
        .build();
  }

  /**
   * {@code <sgSiren>_<invoiceRef>_<yyyyMMddHHmmss>}. Timestamp separator is {@code _} to stay
   * within the spec's allowed character set (alphanumerics + space / dash / plus / underscore
   * / slash).
   */
  private static String composeTransmissionId(InvoicePayableModel model, LocalDateTime at) {
    // sgEntity is guaranteed present: toReportDocument rejects a null or blank one before
    // reaching here, because a transmission with no declarant has nobody to attribute it to.
    // invoiceReference is not guaranteed, so it keeps its fallback.
    String ref = model.getInvoiceReference() != null ? model.getInvoiceReference() : "NOREF";
    return model.getSgEntity() + "_" + ref + "_" + at.format(DATE_TIME_FORMAT);
  }
}
