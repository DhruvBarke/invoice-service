package com.example.invoice.mapper.einvoice;

import com.example.invoice.mapper.einvoice.model.invoice.CurrencyAmount;
import com.example.invoice.mapper.einvoice.model.invoice.InvoiceLine;
import com.example.invoice.mapper.einvoice.model.invoice.Item;
import com.example.invoice.mapper.einvoice.model.invoice.Price;
import com.example.invoice.mapper.einvoice.model.invoice.Quantity;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoiceItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bridges between {@code List<InvoiceItem>} (payable line items, correlated to a parent by
 * {@code invReferenceSg}) and {@code Invoice.invoiceLine}.
 *
 * <p>Ported from A's {@code LineItemMapper} — was a MapStruct {@code @Mapper} interface; now a
 * {@code final} utility class. Two shape changes vs A on the type side:
 *
 * <ul>
 *   <li>feesone {@code Amount} → in-repo {@link CurrencyAmount}.</li>
 *   <li>feesone {@code InvoicedQuantity} → in-repo {@link Quantity} (same
 *       {@code unitCode / value} pair).</li>
 * </ul>
 *
 * <p>Outbound naming: {@code item.name} is "{feeType} {groupingKey}" when both are present,
 * falling back to {@code natureOfExpense} or the line index. The unit price is computed as
 * {@code feeAmount / notionQuantity} when both are populated; otherwise it falls back to
 * {@code feeAmount}.
 *
 * <p>Inbound: each {@link InvoiceLine} becomes one {@link InvoiceItem} with
 * {@code feeAmount = lineExtensionAmount.value}, {@code feeCurrency} from {@code currencyID},
 * and a freshly-minted {@link UUID} in {@code invoiceItemId}.
 */
public final class LineItemMapper {

  private LineItemMapper() {}

  public static List<InvoiceLine> toInvoiceLines(
      List<InvoiceItem> items, String fallbackCurrency, BigDecimal fallbackTotal,
      String fallbackLabel) {
    List<InvoiceLine> lines = new ArrayList<>();
    if (items == null || items.isEmpty()) {
      // Emit a single synthetic line so the UBL has at least one BG-25.
      InvoiceLine syn = new InvoiceLine();
      syn.setId("1");
      CurrencyAmount lea = new CurrencyAmount();
      lea.setCurrencyID(fallbackCurrency);
      lea.setValue(fallbackTotal == null ? BigDecimal.ZERO : fallbackTotal);
      syn.setLineExtensionAmount(lea);
      Item it = new Item();
      it.setName(fallbackLabel == null ? "Invoice line" : fallbackLabel);
      syn.setItem(it);
      Price price = new Price();
      CurrencyAmount pa = new CurrencyAmount();
      pa.setCurrencyID(fallbackCurrency);
      pa.setValue(fallbackTotal == null ? BigDecimal.ZERO : fallbackTotal);
      price.setPriceAmount(pa);
      syn.setPrice(price);
      Quantity qty = new Quantity();
      qty.setUnitCode("C62");
      qty.setValue(BigDecimal.ONE);
      syn.setInvoicedQuantity(qty);
      lines.add(syn);
      return lines;
    }
    int idx = 1;
    for (InvoiceItem item : items) {
      lines.add(toInvoiceLine(item, idx++, fallbackCurrency));
    }
    return lines;
  }

  static InvoiceLine toInvoiceLine(InvoiceItem item, int idx, String fallbackCurrency) {
    InvoiceLine line = new InvoiceLine();
    line.setId(String.valueOf(idx));

    String currency = item.getFeeCurrency() != null ? item.getFeeCurrency() : fallbackCurrency;
    BigDecimal feeAmount = item.getFeeAmount() == null ? BigDecimal.ZERO : item.getFeeAmount();

    CurrencyAmount lea = new CurrencyAmount();
    lea.setCurrencyID(currency);
    lea.setValue(feeAmount);
    line.setLineExtensionAmount(lea);

    Item it = new Item();
    String label;
    if (item.getFeeType() != null && item.getGroupingKey() != null) {
      label = item.getFeeType() + " " + item.getGroupingKey();
    } else if (item.getFeeType() != null) {
      label = item.getFeeType();
    } else if (item.getGroupingKey() != null) {
      label = item.getGroupingKey();
    } else if (item.getNatureOfExpense() != null) {
      label = item.getNatureOfExpense();
    } else {
      label = "Line " + idx;
    }
    it.setName(label);
    line.setItem(it);

    BigDecimal qtyValue =
        item.getNotionQuantity() == null ? BigDecimal.ONE : item.getNotionQuantity();
    Quantity qty = new Quantity();
    qty.setUnitCode("C62");
    qty.setValue(qtyValue);
    line.setInvoicedQuantity(qty);

    Price price = new Price();
    CurrencyAmount pa = new CurrencyAmount();
    pa.setCurrencyID(currency);
    if (qtyValue.compareTo(BigDecimal.ZERO) != 0) {
      pa.setValue(feeAmount.divide(qtyValue, 6, RoundingMode.HALF_UP));
    } else {
      pa.setValue(feeAmount);
    }
    price.setPriceAmount(pa);
    line.setPrice(price);

    return line;
  }

  public static List<InvoiceItem> toInvoiceItems(List<InvoiceLine> lines, String parentReference) {
    List<InvoiceItem> items = new ArrayList<>();
    if (lines == null) return items;
    for (InvoiceLine line : lines) {
      if (line == null) continue;
      InvoiceItem item = new InvoiceItem();
      item.setInvoiceItemId(UUID.randomUUID());
      item.setInvReferenceSg(parentReference);
      CurrencyAmount lea = line.getLineExtensionAmount();
      if (lea != null) {
        item.setFeeAmount(lea.getValue());
        item.setFeeCurrency(lea.getCurrencyID());
      }
      if (line.getItem() != null) {
        // The line's Item.name is the supplier's own free-text label, so it lands on the
        // description and nowhere else. It used to be copied onto feeType as well, which
        // quietly turned arbitrary supplier text into a classification code — feeType is a
        // taxonomy value and belongs to the fee referential, not to whatever the sender typed.
        item.setItemDescription(line.getItem().getName());
      }
      if (line.getInvoicedQuantity() != null) {
        item.setNotionQuantity(line.getInvoicedQuantity().getValue());
      }
      items.add(item);
    }
    return items;
  }

  /** Helper for total reconciliation — sum of line extension amounts. */
  public static BigDecimal sumLineExtensions(List<InvoiceItem> items) {
    if (items == null) return BigDecimal.ZERO;
    BigDecimal acc = BigDecimal.ZERO;
    for (InvoiceItem i : items) {
      if (i != null && i.getFeeAmount() != null) acc = acc.add(i.getFeeAmount());
    }
    return acc;
  }
}
