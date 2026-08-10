package com.sg.thirdparties;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * The body {@code email-service/send-mail} expects.
 *
 * <p>This is the email service's wire shape, not a model of ours. It lives here beside the client
 * that serialises it, for the same reason {@code FeeCategoryResponse} and {@code UploadResponse}
 * do: domain-interface is required to have no dependencies, and a DTO owned by another service is
 * precisely the coupling the module boundaries exist to keep out. The domain's own type is
 * {@link com.sg.domaininterface.model.alerting.EmailMessage}; the client maps between them.
 *
 * <p><b>{@code toAddress} is singular.</b> An {@code EmailMessage} carries a list of recipients,
 * so the client sends one request per address rather than guessing at a separator this endpoint
 * may or may not split on — see {@code RestEmailReferentialClient}.
 */
@Getter
@Setter
@Builder
public class MailRequest {

  private String mailSubject;
  private String mailBody;
  private String fromAddress;
  private String toAddress;
}
