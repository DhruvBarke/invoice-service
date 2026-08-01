package com.example.invoice.mapper.einvoice;

import static com.example.invoice.mapper.einvoice.Constant.*;

import com.example.invoice.mapper.einvoice.DocumentReferenceMapper.AttachmentPayload;
import com.example.invoice.mapper.einvoice.model.invoice.Invoice;
import com.example.invoice.mapper.einvoice.model.invoice.LegalMonetaryTotal;
import com.example.invoice.mapper.einvoice.model.invoice.Period;
import com.example.invoice.mapper.einvoice.model.invoice.TaxTotal;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoiceItem;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoicePayable;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoicePayableModel;
import com.example.invoice.service.domain.model.PartyRegistrationDetails;
import com.example.invoice.service.domain.port.in.PartyRegistrationLookup;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Top-level einvoice ↔ payable facade. The single entry point on each side keeps callers blind
 * to the sub-mapping graph.
 *
 * <p>Ported from A's {@code EInvoiceFacadeMapper} — was a MapStruct-generated Spring bean
 * (abstract class with sub-mapper setters); now a plain final class with one constructor-
 * injected collaborator ({@link PartyRegistrationLookup}). Four shape changes vs A:
 *
 * <ul>
 *   <li><b>@Mapper / @Context / @Named annotations removed.</b> All sub-mapper calls are
 *       explicit static-method invocations; there is no bean formation to fail.</li>
 *   <li><b>{@code PartyReferentialClient} replaced by {@link PartyRegistrationLookup}.</b>
 *       The inbound path now resolves supplier and customer parties through the shared party-
 *       registration referential rather than a bespoke referential client. Fields on
 *       {@link PartyRegistrationDetails} that don't map onto A's original {@code PartyInfo}
 *       ({@code group}, {@code lei}, {@code internalCode}) are left {@code null} on the
 *       resulting {@link InvoicePayable} — see the field-level notes in
 *       {@link #enrichFromLookup(InvoicePayable, InvoicePayableModel, String, String)}.</li>
 *   <li><b>{@code SgDocV3Client} removed.</b> A's outbound path fetched attachment bytes from
 *       an sgdoc port passed via {@code @Context}. Per the migration decision, callers now
 *       pass the raw bytes directly through
 *       {@link #toEInvoice(InvoicePayableModel, List, AttachmentPayload, AttachmentPayload)}.
 *       Fetching is the caller's concern — the mapper is a pure transformation.</li>
 *   <li><b>{@code MappedResult} kept as a nested record.</b> The einvoice → payable direction
 *       still needs to hand back both the model and its line items; that's still a record.</li>
 * </ul>
 *
 * <p>Party convention (confirmed 2026-07-01): <strong>provider is the UBL supplier; SG is the
 * UBL customer</strong>. Inbound extracts the supplier SIREN as the provider's and the
 * customer SIREN as SG's, then resolves canonical names / internal codes / mnemonics through
 * the referential.
 *
 * <p>Inbound side-effects: <em>none</em>. The mapper does NOT push attachments to any document
 * store — {@link MultipartExtractionService} pulls raw bytes out of the invoice; the
 * registration endpoint is responsible for uploading them and back-filling
 * {@code invoicePdfId} / {@code invoiceExcelId} on the returned {@link InvoicePayable}.
 */
public final class EInvoiceFacadeMapper {

  private final PartyRegistrationLookup lookup;

  public EInvoiceFacadeMapper(PartyRegistrationLookup lookup) {
    this.lookup = Objects.requireNonNull(lookup, "lookup");
  }

  /** Result carrier for einvoice → payable: produces both the model and its items. */
  public record MappedResult(InvoicePayableModel model, List<InvoiceItem> items) {}

  // ── outbound: Payable → einvoice ────────────────────────────────────────

  /**
   * Build a UBL {@link Invoice} from a payable model + line items.
   *
   * @param model the invoice envelope
   * @param items zero or more invoice lines
   * @param pdf   optional PDF attachment; embedded at {@code additionalDocumentReference[0]}
   * @param excel optional Excel attachment; embedded at {@code additionalDocumentReference[1]}
   */
  public Invoice toEInvoice(
      InvoicePayableModel model,
      List<InvoiceItem> items,
      AttachmentPayload pdf,
      AttachmentPayload excel) {
    if (model == null) return null;
    Invoice inv = new Invoice();
    inv.setUblVersionId(UBL_VERSION_ID);
    inv.setCustomizationId(CUSTOMIZATION_ID);
    inv.setProfileId(PROFILE_ID);
    inv.setId(model.getInvoiceReference());
    inv.setIssueDate(model.getInvoiceDate());
    inv.setInvoiceTypeCode(InvoiceTypeMapper.toInvoiceTypeCode(model.getInvoiceType()));
    inv.setDocumentCurrencyCode(AmountMapper.toCodedCurrency(model.getCurrency()));

    if (model.getTradingStartDate() != null || model.getTradingEndDate() != null) {
      Period period = new Period();
      period.setStartDate(model.getTradingStartDate());
      period.setEndDate(model.getTradingEndDate());
      inv.setInvoicePeriod(period);
    }

    InvoicePayable payable = model.getInvoicePayable();
    BigDecimal vatAmount = payable == null ? null : payable.getVatAmount();
    BigDecimal vatRate = payable == null ? null : payable.getVatRate();

    // Provider → supplier, SG → customer.
    inv.setAccountingSupplierParty(
        PartyMapper.toSupplier(
            model.getProviderId(),
            payable == null ? null : payable.getProviderName()));
    inv.setAccountingCustomerParty(
        PartyMapper.toCustomer(
            model.getSgEntity(),
            payable == null ? null : payable.getSgEntityName()));

    BigDecimal totalInclTax = model.getAmount();
    LegalMonetaryTotal totals =
        AmountMapper.toLegalMonetaryTotal(totalInclTax, vatAmount, model.getCurrency());
    inv.setLegalMonetaryTotal(totals);

    BigDecimal taxableBase = totals == null ? null : totals.getLineExtensionAmount().getValue();
    List<TaxTotal> tax =
        AmountMapper.toTaxTotal(vatAmount, vatRate, taxableBase, model.getCurrency());
    inv.setTaxTotal(tax);

    inv.setInvoiceLine(
        LineItemMapper.toInvoiceLines(
            items,
            model.getCurrency(),
            totals == null ? totalInclTax : totals.getLineExtensionAmount().getValue(),
            payable == null ? model.getRefCptyId() : payable.getProviderReference()));

    // Attachments passed as raw payloads by the caller. Nothing is fetched here.
    if (pdf != null || excel != null) {
      inv.setAdditionalDocumentReference(
          DocumentReferenceMapper.toAdditionalDocumentReferences(pdf, excel));
    }

    return inv;
  }

  // ── inbound: einvoice → Payable ─────────────────────────────────────────

  /**
   * Build the {@code InvoicePayableModel} + lines from an inbound einvoice.
   *
   * <p>{@code invoicePdfId} / {@code invoiceExcelId} are deliberately left {@code null} on the
   * returned {@code InvoicePayable}; the registration endpoint populates them once it has
   * stored the extracted files (from {@link MultipartExtractionService}) in the document
   * store.
   */
  public MappedResult toInvoicePayable(Invoice inv) {
    if (inv == null) return new MappedResult(null, List.of());

    InvoicePayableModel model = new InvoicePayableModel();
    InvoicePayable payable = new InvoicePayable();

    model.setInvoiceReference(inv.getId());
    model.setInvoiceDate(inv.getIssueDate());
    model.setInvoiceType(InvoiceTypeMapper.toInvoiceType(inv.getInvoiceTypeCode()));
    model.setInvoiceStatus(INVOICE_STATUS_REGISTERED);
    model.setFeeCategory(FEE_CATEGORY);

    // Provider = supplier, SG = customer.
    String providerSiren =
        PartyMapper.extractProviderRegistrationNumber(inv.getAccountingSupplierParty());
    String sgSiren =
        PartyMapper.extractSgEntityRegistrationNumber(inv.getAccountingCustomerParty());

    enrichFromLookup(payable, model, providerSiren, sgSiren);

    if (inv.getInvoicePeriod() != null) {
      model.setTradingStartDate(inv.getInvoicePeriod().getStartDate());
      model.setTradingEndDate(inv.getInvoicePeriod().getEndDate());
    }

    String currency = AmountMapper.fromCodedCurrency(inv.getDocumentCurrencyCode());
    model.setCurrency(currency);
    payable.setCurrency(currency);

    BigDecimal payableAmount =
        inv.getLegalMonetaryTotal() == null
            ? null
            : AmountMapper.value(inv.getLegalMonetaryTotal().getPayableAmount());
    model.setAmount(payableAmount);
    payable.setAmountIncludingTax(payableAmount);
    if (payableAmount != null) payable.setInvoicedAmount(payableAmount.toString());

    payable.setIssueDate(inv.getIssueDate());
    payable.setVatAmount(AmountMapper.firstVatAmount(inv.getTaxTotal()));
    payable.setVatRate(AmountMapper.firstVatRate(inv.getTaxTotal()));

    payable.setFeeCategoryCode(FEE_CATEGORY_CODE);
    payable.setProviderReference(inv.getId());

    // Attachments: left null. MultipartExtractionService produces the raw bytes separately,
    // and the registration endpoint sets these ids after storing the files.
    payable.setInvoicePdfId(null);
    payable.setInvoiceExcelId(null);

    model.setInvoicePayable(payable);

    List<InvoiceItem> items = LineItemMapper.toInvoiceItems(inv.getInvoiceLine(), inv.getId());
    return new MappedResult(model, items);
  }

  /**
   * Resolve provider and SG parties through {@link PartyRegistrationLookup} and write their
   * canonical names / codes / mnemonics onto the payable + model.
   *
   * <p>Field mapping vs A's original {@code PartyReferentialClient.PartyInfo}:
   * <ul>
   *   <li>{@code PartyInfo.name}         → {@link PartyRegistrationDetails#name()}</li>
   *   <li>{@code PartyInfo.mnemonic}     → {@link PartyRegistrationDetails#mnemonic()}</li>
   *   <li>{@code PartyInfo.internalCode} → {@link PartyRegistrationDetails#goldenBdrId()}
   *       (both are the SG-internal party identity)</li>
   *   <li>{@code PartyInfo.group}, {@code PartyInfo.lei} — no equivalent on the shared
   *       referential yet; left {@code null}. If a caller needs them, extend
   *       {@link PartyRegistrationDetails} or add a supplementary lookup port.</li>
   * </ul>
   */
  private void enrichFromLookup(
      InvoicePayable payable, InvoicePayableModel model, String providerSiren, String sgSiren) {
    Optional<PartyRegistrationDetails> providerInfo =
        providerSiren == null ? Optional.empty() : lookup.findBySiren(providerSiren);
    Optional<PartyRegistrationDetails> sgInfo =
        sgSiren == null ? Optional.empty() : lookup.findBySiren(sgSiren);

    // model-level identifiers
    model.setSgEntity(sgSiren);
    // providerId on the model gets the referential's internal id, falling back to the SIREN.
    model.setProviderId(providerInfo.map(PartyRegistrationDetails::goldenBdrId).orElse(providerSiren));

    // SG-side payable fields
    payable.setSgEntityCode(sgInfo.map(PartyRegistrationDetails::goldenBdrId).orElse(null));
    payable.setSgEntityName(sgInfo.map(PartyRegistrationDetails::name).orElse(null));
    payable.setSgEntityMnemonic(sgInfo.map(PartyRegistrationDetails::mnemonic).orElse(null));

    // Provider-side payable fields — group / lei have no equivalent yet on
    // PartyRegistrationDetails (see class Javadoc).
    payable.setProviderName(providerInfo.map(PartyRegistrationDetails::name).orElse(null));
    payable.setProviderMnemo(providerInfo.map(PartyRegistrationDetails::mnemonic).orElse(null));
    payable.setProviderGroup(null);
    payable.setLeiDetails(null);
  }
}
