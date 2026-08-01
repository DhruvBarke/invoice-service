package com.example.invoice.mapper.einvoice.model.payableinvoice;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class InvoicePayable implements Serializable {

  private static final long serialVersionUID = 2427651814712071338L;

  private BigInteger invRef;
  private BigInteger lastBapRef;
  private String typeOfInv;
  private String typeOfBroking;
  private String natureOfBroking;
  private String invStatus;
  private BigInteger cptyIdentifier;
  private String brokerInvRef;

  private LocalDate emissionDate;

  private LocalDate issueDate;

  private String rcCode;
  private String currency;
  private BigDecimal amountIncludingTax;
  private BigDecimal vatAmount;
  private String conversionCurrency;
  private BigDecimal vatRate;
  private BigDecimal discountAmount;

  private LocalDate createdDate;

  private String createdByUser;
  private String comments;

  private LocalDate lastUpdatedDate;

  private String lastUpdatedByUser;

  private LocalDate cancellationDate;

  private String cancelledBy;
  private String cancellationReason;
  private String bapUpdateIndicator;

  private LocalDate bapUpdateDate;

  private String bapUpdateByUser;

  private LocalDate closingDate;

  private String closedByUser;
  private String numOfDecimals;
  private String invoicedAmount;

  private LocalDate paymentDueDate;

  private String paymentType;
  private BigInteger familyCode;
  private String entityCode;
  private String userRcCode;
  private Integer pdfId;
  private String invoicePdfId;
  private String isinCode;
  private Integer invIndicator;
  private String appOriginCode;

  private LocalDate pdfGenerationDate;

  private LocalDate pdfAssetGenerationDate;

  private LocalDate finalAccountingDate;

  private String assetPdfIndicator;
  private String invProvision;
  private String workgroupMnemo;
  private String escalatorIndicator;
  private Integer priorityId;
  private String userAssigned;
  private BigInteger lineNum;
  private BigInteger dealNum;
  private String eventComment;
  private String followupComment;
  private String jediId;
  private String parentInvIndicator;
  private BigInteger parentInvRef;
  private String providerName;
  private String providerMnemo;
  private String providerReference;
  private String providerGroup;
  private String frequency;

  private BigDecimal taxAmount;
  private String accountNumber;
  private String memberId;
  private String ssiSwiftCode;
  private String ssiAccountCode;
  private String ssiBankDetail;
  private String paymentMethod;
  private String feeCategory;
  private String feeCategoryCode;
  private Integer feeBdrId;
  private String sgEntityCode;
  private String sgEntityName;
  private String sgEntityMnemonic;
  private String marketCode;
  private String marketLabel;
  private String marketCity;
  private String leiDetails;
  private String amountToEur;
  private String icId;

  private LocalDate assignmentDate;

  private String invoiceExcelId;
  private BigDecimal sumOfPaidTrades;
  private BigDecimal sumOfAllegeTrades;
  private BigDecimal sumOfRateMismatchTrades;
  private BigDecimal sumOfPartialMatchTrades;
  private BigDecimal sumOfGoodToPayTrades;
  private String feeType;
  private UUID parentId;
  private List<UUID> childIds;
  private String clientType;
  private String clientName;
  private Boolean paymentFlag;
  private Boolean accountingFlag;

  public static InvoicePayable copyFactory(InvoicePayable original) {
    return InvoicePayable.builder()
        .invRef(original.getInvRef())
        .lastBapRef(original.getLastBapRef())
        .typeOfInv(original.getTypeOfInv())
        .typeOfBroking(original.getTypeOfBroking())
        .natureOfBroking(original.natureOfBroking)
        .invStatus(original.getInvStatus())
        .cptyIdentifier(original.getCptyIdentifier())
        .brokerInvRef(original.getBrokerInvRef())
        .emissionDate(original.getEmissionDate())
        .issueDate(original.getIssueDate())
        .rcCode(original.getRcCode())
        .currency(original.getCurrency())
        .amountIncludingTax(original.getAmountIncludingTax())
        .vatAmount(original.getVatAmount())
        .conversionCurrency(original.getConversionCurrency())
        .vatRate(original.getVatRate())
        .discountAmount(original.getDiscountAmount())
        .createdDate(original.getCreatedDate())
        .createdByUser(original.createdByUser)
        .comments(original.getComments())
        .lastUpdatedDate(original.lastUpdatedDate)
        .lastUpdatedByUser(original.getLastUpdatedByUser())
        .cancellationDate(original.cancellationDate)
        .cancelledBy(original.getCancelledBy())
        .cancellationReason(original.cancellationReason)
        .bapUpdateIndicator(original.getBapUpdateIndicator())
        .bapUpdateDate(original.bapUpdateDate)
        .bapUpdateByUser(original.bapUpdateByUser)
        .closingDate(original.closingDate)
        .closedByUser(original.closedByUser)
        .numOfDecimals(original.numOfDecimals)
        .invoicedAmount(original.invoicedAmount)
        .paymentDueDate(original.paymentDueDate)
        .paymentType(original.paymentType)
        .familyCode(original.familyCode)
        .entityCode(original.entityCode)
        .userRcCode(original.userRcCode)
        .pdfId(original.getPdfId())
        .invoicePdfId(original.getInvoicePdfId())
        .isinCode(original.isinCode)
        .invIndicator(original.invIndicator)
        .appOriginCode(original.appOriginCode)
        .pdfGenerationDate(original.pdfGenerationDate)
        .pdfAssetGenerationDate(original.pdfAssetGenerationDate)
        .finalAccountingDate(original.finalAccountingDate)
        .assetPdfIndicator(original.assetPdfIndicator)
        .invProvision(original.invProvision)
        .workgroupMnemo(original.workgroupMnemo)
        .escalatorIndicator(original.escalatorIndicator)
        .priorityId(original.getPriorityId())
        .userAssigned(original.userAssigned)
        .lineNum(original.lineNum)
        .dealNum(original.dealNum)
        .eventComment(original.eventComment)
        .followupComment(original.followupComment)
        .jediId(original.jediId)
        .parentInvIndicator(original.parentInvIndicator)
        .parentInvRef(original.parentInvRef)
        .providerName(original.providerName)
        .providerMnemo(original.providerMnemo)
        .providerReference(original.providerReference)
        .providerGroup(original.providerGroup)
        .frequency(original.frequency)
        .taxAmount(original.taxAmount)
        .accountNumber(original.getAccountNumber())
        .memberId(original.memberId)
        .ssiSwiftCode(original.ssiSwiftCode)
        .ssiAccountCode(original.ssiAccountCode)
        .ssiBankDetail(original.ssiBankDetail)
        .paymentMethod(original.paymentMethod)
        .feeCategory(original.feeCategory)
        .feeCategoryCode(original.getFeeCategoryCode())
        .feeBdrId(original.feeBdrId)
        .sgEntityCode(original.entityCode)
        .sgEntityName(original.sgEntityName)
        .sgEntityMnemonic(original.getSgEntityMnemonic())
        .marketCode(original.marketCode)
        .marketLabel(original.marketLabel)
        .marketCity(original.marketCity)
        .leiDetails(original.leiDetails)
        .amountToEur(original.amountToEur)
        .icId(original.getIcId())
        .assignmentDate(original.assignmentDate)
        .invoiceExcelId(original.invoiceExcelId)
        .sumOfPaidTrades(original.sumOfPaidTrades)
        .sumOfAllegeTrades(original.sumOfAllegeTrades)
        .sumOfRateMismatchTrades(original.sumOfRateMismatchTrades)
        .sumOfPartialMatchTrades(original.sumOfPartialMatchTrades)
        .sumOfGoodToPayTrades(original.sumOfGoodToPayTrades)
        .feeType(original.feeType)
        .parentId(original.parentId)
        .childIds(original.childIds)
        .clientType(original.clientType)
        .clientName(original.clientName)
        .paymentFlag(original.paymentFlag)
        .build();
  }
}
