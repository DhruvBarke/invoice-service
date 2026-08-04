package com.sg.mapper.report;

import static com.sg.mapper.einvoice.Constant.*;

import com.sg.domaininterface.model.payableinvoice.InvoiceItem;
import com.sg.domaininterface.model.payableinvoice.InvoicePayable;
import com.sg.domaininterface.model.payableinvoice.InvoicePayableModel;
import com.sg.domaininterface.model.report.BusinessProcess;
import com.sg.domaininterface.model.report.Invoice;
import com.sg.domaininterface.model.report.InvoicePeriod;
import com.sg.domaininterface.model.report.TaxSubTotal;
import java.math.BigDecimal;
import java.util.List;

/**
 * Builds the per-invoice payload (TG-8 {@code Invoice}) inside a {@link
 * com.sg.domaininterface.model.report.TransactionsReport} from an {@link
 * InvoicePayableModel} + its line items.
 *
 * <p>Ported from A's {@code ReportInvoiceMapper} — was a MapStruct-generated {@code abstract
 * class}; now a {@code final} utility class with static methods. Two shape changes vs A:
 *
 * <ul>
 *   <li><b>{@code PartyReferentialClient} parameter removed.</b> A's version threaded a
 *       referential through here but never used it (the body had
 *       {@code if (referential != null) { /* no-op *&#47; }}); dropped.</li>
 *   <li><b>Interface + default methods → final class + static methods.</b> No sub-mapper
 *       fields; every collaborator call is a fully-qualified static invocation.</li>
 * </ul>
 *
 * <p>The einvoice-side UNTDID 1001 codes (380/381/384) are reused inline rather than through
 * the {@link com.sg.mapper.einvoice.InvoiceTypeMapper} — {@code toUntdid1001} is
 * a two-line switch, and pulling the einvoice mapper in cross-references the two sub-packages
 * unnecessarily.
 */
public final class ReportInvoiceMapper {

  private ReportInvoiceMapper() {}

  /**
   * Compose a Flux 10 {@link Invoice} from a payable model + its lines.
   *
   * @param model source InvoicePayableModel (mandatory).
   * @param items line items; may be null / empty — {@code Invoice.line} stays null.
   * @param config env config for {@code BusinessProcess} defaults.
   */
  public static Invoice toInvoice(
      InvoicePayableModel model, List<InvoiceItem> items, ReportFlowConfig config) {

    if (model == null) throw new ReportMappingException("InvoicePayableModel is null");
    if (model.getInvoiceReference() == null || model.getInvoiceReference().isBlank()) {
      throw new ReportMappingException("InvoicePayableModel.invoiceReference is required");
    }
    if (model.getCurrency() == null || model.getCurrency().isBlank()) {
      throw new ReportMappingException("InvoicePayableModel.currency is required");
    }

    InvoicePayable payable = model.getInvoicePayable();
    BigDecimal amountInclTax = model.getAmount();
    BigDecimal vatAmount = payable != null ? payable.getVatAmount() : null;
    BigDecimal vatRate = payable != null ? payable.getVatRate() : null;
    if (vatAmount == null && payable != null) vatAmount = payable.getTaxAmount();

    Invoice.InvoiceBuilder b = Invoice.builder()
        .id(model.getInvoiceReference())
        .issueDate(model.getInvoiceDate())
        .typeCode(untdid1001From(model.getInvoiceType()))
        .currencyCode(model.getCurrency())
        .dueDate(payable != null ? payable.getPaymentDueDate() : null)
        .businessProcess(defaultBusinessProcess(config));

    // Provider country not carried on the source model — pass null and let the party mapper
    // default to FR.
    b.seller(ReportPartyMapper.toSeller(model.getProviderId(), null));
    b.buyer(ReportPartyMapper.toBuyer(model.getSgEntity(), null));

    if (model.getTradingStartDate() != null || model.getTradingEndDate() != null) {
      b.invoicePeriod(InvoicePeriod.builder()
          .startDate(model.getTradingStartDate())
          .endDate(model.getTradingEndDate())
          .build());
    }

    b.monetaryTotal(ReportTotalsMapper.toMonetaryTotal(amountInclTax, vatAmount, model.getCurrency()));

    BigDecimal taxable = amountInclTax != null
        ? amountInclTax.subtract(vatAmount != null ? vatAmount : BigDecimal.ZERO)
        : null;
    List<TaxSubTotal> tst = ReportTotalsMapper.toTaxSubTotals(taxable, vatAmount, vatRate);
    if (!tst.isEmpty()) b.taxSubTotal(tst);

    b.line(ReportLineMapper.toInvoiceLines(items));

    return b.build();
  }

  private static String untdid1001From(String invoiceType) {
    if (invoiceType == null) return INVOICE_TYPE_DEBIT;
    return switch (invoiceType.toUpperCase()) {
      case DEBIT -> INVOICE_TYPE_DEBIT;
      case CREDIT -> INVOICE_TYPE_CREDIT;
      case CORRECTED -> INVOICE_TYPE_CORRECTED;
      default -> INVOICE_TYPE_DEBIT;
    };
  }

  private static BusinessProcess defaultBusinessProcess(ReportFlowConfig config) {
    if (config == null) return null;
    return BusinessProcess.builder()
        .id(config.getDefaultBusinessProcessId())
        .typeId(config.getDefaultBusinessProcessTypeId())
        .build();
  }
}
