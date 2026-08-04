package com.sg.domaininterface.model.invoice;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.*;

/**
 * Canonical invoice model matching eInvoice_model_v8_1.json.
 * Stored as JSONB in t_e_invoice.data column.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Invoice {

    // UBL version identifier
    private String ublVersionId;
    // BT-1: Invoice number
    private String id;
    // BT-2: Issue date
    private LocalDate issueDate;
    // BT-3: Invoice type code
    private CodedValue invoiceTypeCode;
    // BT-5: Document currency code
    private CodedValue documentCurrencyCode;
    // BT-6: Tax currency code
    private CodedValue taxCurrencyCode;
    // BT-9: Due date
    private LocalDate dueDate;
    // BT-10: Buyer reference
    private String buyerReference;
    // BT-19: Accounting cost
    private String accountingCost;
    // BT-23: Profile ID (billing framework)
    private String profileId;
    // BT-24: Customization ID (specification identifier)
    private String customizationId;
    // BT-20: Payment terms
    private PaymentTerms paymentTerms;

    // BG-14: Invoice period
    private Period invoicePeriod;

    // BG-1: Notes
    @Builder.Default
    private List<NoteEntry> note = new ArrayList<>();

    // BT-13: Order reference
    private OrderReference orderReference;

    // BT-12: Contract document reference
    private ContractDocumentReference contractDocumentReference;

    // BT-18: Document reference (invoiced object identifier)
    private DocumentReference documentReference;

    // BG-3: Billing references (preceding invoices)
    @Builder.Default
    private List<BillingReference> billingReference = new ArrayList<>();

    // BG-24: Additional document references
    @Builder.Default
    private List<AdditionalDocumentReference> additionalDocumentReference = new ArrayList<>();

    // BG-4/5/6: Seller
    private AccountingSupplierParty accountingSupplierParty;

    // BG-7/8/9: Buyer
    private AccountingCustomerParty accountingCustomerParty;

    // BG-11: Tax representative
    private TaxRepresentativeParty taxRepresentativeParty;

    // BG-10: Payee (full party in v8_1)
    private Party payeeParty;

    // EXT-FR-FE-BG-14: Delivery terms / Incoterms
    private DeliveryTerms deliveryTerms;

    // BG-16: Payment means (array)
    @Builder.Default
    private List<PaymentMeans> paymentMeans = new ArrayList<>();

    // BG-20/21: Allowances and charges
    @Builder.Default
    private List<AllowanceCharge> allowanceCharge = new ArrayList<>();

    // BG-13: Delivery information
    private Delivery delivery;

    // BG-22: Legal monetary total
    private LegalMonetaryTotal legalMonetaryTotal;

    // BG-22/23: Tax total (array)
    @Builder.Default
    private List<TaxTotal> taxTotal = new ArrayList<>();

    // BG-25: Invoice lines
    @Builder.Default
    private List<InvoiceLine> invoiceLine = new ArrayList<>();

    // === BACKWARD-COMPATIBLE EXTRA FIELDS ===
    // BT-7: Tax point date
    private LocalDate taxPointDate;
    // BT-11: Project reference
    private String projectReference;
    // BT-15: Receipt document reference
    private String receiptDocumentReference;
    // BT-16: Despatch document reference
    private String despatchDocumentReference;
    // BT-17: Originator document reference
    private String originatorDocumentReference;

    // Derived helpers
    public int getIssueYear() { return issueDate != null ? issueDate.getYear() : 0; }

    // Convenience value accessors for downstream code
    public String getInvoiceTypeCodeValue() {
        return invoiceTypeCode != null ? invoiceTypeCode.getValue() : null;
    }
    public String getDocumentCurrencyCodeValue() {
        return documentCurrencyCode != null ? documentCurrencyCode.getValue() : null;
    }
    public String getTaxCurrencyCodeValue() {
        return taxCurrencyCode != null ? taxCurrencyCode.getValue() : null;
    }

    public String getSellerSiren() {
        if (accountingSupplierParty != null && accountingSupplierParty.getParty() != null) {
            var ple = accountingSupplierParty.getParty().getPartyLegalEntity();
            if (ple != null && ple.getCompanyId() != null) return ple.getCompanyId().getValue();
        }
        return null;
    }

    public Party sellerParty() {
        return accountingSupplierParty != null ? accountingSupplierParty.getParty() : null;
    }

    public Party buyerParty() {
        return accountingCustomerParty != null ? accountingCustomerParty.getParty() : null;
    }
}
