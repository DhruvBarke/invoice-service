package com.sg.mapper.report;

import com.sg.domaininterface.model.payableinvoice.InvoiceItem;
import com.sg.domaininterface.model.report.BilledQuantity;
import com.sg.domaininterface.model.report.InvoiceLine;
import com.sg.domaininterface.model.report.LineNote;
import com.sg.domaininterface.model.report.Price;
import com.sg.domaininterface.model.report.Product;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Translates the invoice-service's line items ({@link InvoiceItem}) into Flux 10 {@link
 * InvoiceLine}s (TG-24, BG-25).
 *
 * <p>Ported from A's {@code ReportLineMapper} — was a MapStruct {@code @Mapper} interface; now
 * a {@code final} utility class with static methods.
 *
 * <p>Per the user's directive: if the caller passes no items (null or empty list), this mapper
 * returns {@code null} so the resulting {@code Invoice.line} field stays absent.
 *
 * <p>Field map per item:
 * <ul>
 *   <li>{@code Product.name} ← {@code feeType} fallback {@code groupingKey} /
 *       {@code natureOfExpense} / {@code itemDescription}.</li>
 *   <li>{@code BilledQuantity.value} ← {@code notionQuantity}, default {@code 1};
 *       {@code unitCode} fixed to {@code "EA"} (each).</li>
 *   <li>{@code Price.priceAmount} ← {@code feeAmount}.</li>
 *   <li>{@code LineNote.code} ← {@code feeType} when distinct from product name;
 *       {@code .comment} ← {@code itemDescription}.</li>
 * </ul>
 */
public final class ReportLineMapper {

  /** UN/ECE Recommendation 20 default quantity unit. */
  public static final String DEFAULT_UNIT_CODE = "EA";

  private ReportLineMapper() {}

  /** Returns {@code null} when {@code items} is null or empty. */
  public static List<InvoiceLine> toInvoiceLines(List<InvoiceItem> items) {
    if (items == null || items.isEmpty()) return null;
    List<InvoiceLine> out = new ArrayList<>(items.size());
    for (InvoiceItem item : items) {
      if (item == null) continue;
      out.add(toInvoiceLine(item));
    }
    return out.isEmpty() ? null : out;
  }

  public static InvoiceLine toInvoiceLine(InvoiceItem item) {
    if (item == null) return null;
    InvoiceLine.InvoiceLineBuilder b = InvoiceLine.builder();

    // Product
    String name = firstNonBlank(
        item.getFeeType(), item.getGroupingKey(),
        item.getNatureOfExpense(), item.getItemDescription());
    if (name != null) {
      b.product(Product.builder().name(name).build());
    }

    // BilledQuantity
    b.billedQuantity(BilledQuantity.builder()
        .value(item.getNotionQuantity() != null ? item.getNotionQuantity() : BigDecimal.ONE)
        .unitCode(DEFAULT_UNIT_CODE)
        .build());

    // Price
    if (item.getFeeAmount() != null) {
      b.price(Price.builder().priceAmount(item.getFeeAmount()).build());
    }

    // Notes — only emit when there's actual content distinct from the product name
    String comment = item.getItemDescription();
    String code = item.getFeeType();
    boolean codeUsedAsName = name != null && name.equals(code);
    if (comment != null || (code != null && !codeUsedAsName)) {
      b.note(List.of(LineNote.builder()
          .code(codeUsedAsName ? null : code)
          .comment(comment)
          .build()));
    }

    return b.build();
  }

  private static String firstNonBlank(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) return v;
    }
    return null;
  }
}
