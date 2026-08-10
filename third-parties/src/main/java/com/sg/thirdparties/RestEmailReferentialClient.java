package com.sg.thirdparties;

import com.sg.domaininterface.model.alerting.EmailMessage;
import com.sg.domaininterface.port.out.AlertEmailPort;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * {@link AlertEmailPort} over the {@code email-service/send-mail} referential endpoint.
 *
 * <p>This is the implementation the alerting stack has been missing. {@code AlertEmailPort} had
 * no bean at all, so {@code emailAlertPublisher} could not be constructed and the context would
 * not start — the port was declared, the publisher was written and tested against it, and nothing
 * ever answered it.
 *
 * <p><b>One request per recipient.</b> {@link EmailMessage} carries a list; {@link MailRequest}
 * has a single {@code toAddress}. Rather than guess at a separator the endpoint may or may not
 * split on, each address gets its own call. The failure modes decide it: sending three messages
 * where one was meant is a little noise, while a comma-joined address the service does not parse
 * is an alert nobody receives. If the endpoint does accept a list, collapsing this to one call is
 * a two-line change.
 *
 * <p><b>A partial failure is still a failure.</b> If some recipients are reached and others are
 * not, this raises — with the ones that did get through named in the message, so nobody re-sends
 * to everyone to cover a gap that affected one address.
 */
public final class RestEmailReferentialClient implements AlertEmailPort {

  private final RestTemplate restTemplate;
  private final ReferentialProperties properties;
  private final String fromAddress;

  /**
   * @param fromAddress the sender the mail service should put on the message. Configuration
   *                    rather than a constant: it differs per environment, and a production
   *                    alert arriving from a test mailbox is one people learn to ignore.
   */
  public RestEmailReferentialClient(RestTemplate restTemplate, ReferentialProperties properties,
                                    String fromAddress) {
    this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.fromAddress = Objects.requireNonNull(fromAddress, "fromAddress");
  }

  @Override
  public void send(EmailMessage message) {
    Objects.requireNonNull(message, "message");
    URI uri = UriComponentsBuilder
        .fromUriString(properties.emailBaseUrl() + "/email-service/send-mail")
        .build().encode().toUri();

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    List<String> delivered = new ArrayList<>(message.to().size());
    for (String recipient : message.to()) {
      MailRequest request = MailRequest.builder()
          .mailSubject(message.subject())
          .mailBody(message.body())
          .fromAddress(fromAddress)
          .toAddress(recipient)
          .build();
      try {
        restTemplate.exchange(uri, HttpMethod.POST,
            new HttpEntity<>(request, headers), String.class);
        delivered.add(recipient);
      } catch (RestClientException e) {
        throw new AlertEmailPort.EmailDispatchException(
            "could not send '" + message.subject() + "' to " + recipient
                + deliveredSuffix(delivered, message.to()), e);
      }
    }
  }

  /**
   * Names who already received the message, when some did.
   *
   * <p>Without it the caller knows only that the send failed, and re-sending to the whole list to
   * be safe means everyone who already got it gets it twice.
   */
  private static String deliveredSuffix(List<String> delivered, List<String> all) {
    if (delivered.isEmpty()) {
      return " (no recipient was reached)";
    }
    return " (already delivered to " + String.join(", ", delivered)
        + "; " + delivered.size() + " of " + all.size() + " sent)";
  }
}
