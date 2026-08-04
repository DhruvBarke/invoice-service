package com.sg.domaininterface.model.invoice;

import lombok.*;
import java.util.ArrayList;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class InvoiceLine {
    private String id;
    @Builder.Default
    private List<NoteEntry> note = new ArrayList<>();

    // Line-level billing reference
    private BillingReference billingReference;

    // Line references
    private DespatchLineReference despatchLineReference;
    private ReceiptLineRef receiptLineReference;
    private OrderLineRef orderLineReference;
    private DocumentReference documentReference;
    private String accountingCost;

    // Line-level delivery
    private Delivery delivery;

    // Line-level period
    private Period invoicePeriod;

    // Line-level allowances/charges
    @Builder.Default
    private List<AllowanceCharge> allowanceCharge = new ArrayList<>();

    // Price
    private Price price;

    // EXT-FR: Line-level tax total
    private TaxTotal taxTotal;

    // Item
    private Item item;

    // Quantity and amount
    private Quantity invoicedQuantity;
    private CurrencyAmount lineExtensionAmount;
}
