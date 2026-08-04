package com.sg.jpa.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One {@code publicinvoice.t_invoice_items} row.
 *
 * <p>Field-for-field with the target JPA entity, minus the annotations — see
 * {@link InvoicePayableEntity} for why.
 *
 * <p><b>The correlation column is {@code invReferenceSg}, not {@code invoiceReference}.</b> It
 * holds SG's own reference for the parent invoice, minted from the sequence at persist time, and
 * is stamped onto every item by the store. The mapper deliberately leaves it null: the only
 * reference available at mapping time is the supplier's, which is unique within that supplier
 * and would be the wrong invoice's key sitting in SG's column.
 *
 * <p>{@code tradedCurrency}, {@code tradedAmount} and {@code fxRate} are String in the target
 * entity even though two of them are numbers. Mirrored rather than corrected: changing the type
 * here would not change the entity and would break the other writers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceItemEntity {

  private UUID invoiceItemId;

  /** SG's reference for the parent invoice. Stamped by the store, never by the mapper. */
  private String invReferenceSg;

  private String feeType;
  private String groupingKey;
  private String natureOfExpense;
  private String accountNumber;
  private String product;

  private BigDecimal notionQuantity;
  private BigDecimal feeAmount;
  private String feeCurrency;
  private BigDecimal providerRate;

  private BigDecimal exchangedRate;
  private BigDecimal exchangedAmount;
  private String exchangedAmountCurrency;

  private BigDecimal vatAmount;
  private String vatAmountCurrency;
  private String debitCredit;

  private LocalDate itemsCreationDate;
  private String itemsCreationUser;
  private LocalDate itemsLastUpdateDate;
  private String itemsLastUpdateUser;

  /** The invoice line's own label, as the supplier wrote it. */
  private String itemDescription;

  private String marketRegion;
  private String feeAgreement;
  private String business;

  private String tradedCurrency;
  private String tradedAmount;
  private String fxRate;
}
