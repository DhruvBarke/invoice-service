package com.sg.domaininterface.model.invoice;

import lombok.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PaymentMeans {
    private CodedValue paymentMeansCode;
    private String paymentMeansCodeName;

    public String getPaymentMeansCodeValue() {
        return paymentMeansCode != null ? paymentMeansCode.getValue() : null;
    }
    private String paymentId;
    private String paymentDueDate;
    private String paymentChannelCode;
    @Builder.Default
    private List<PayeeFinancialAccount> payeeFinancialAccount = new ArrayList<>();
    private Map<String, Object> cardAccount;
    private PaymentMandate paymentMandate;
}
