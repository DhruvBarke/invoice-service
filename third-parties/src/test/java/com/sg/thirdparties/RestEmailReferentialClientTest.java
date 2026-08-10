package com.sg.thirdparties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sg.domaininterface.model.alerting.EmailMessage;
import com.sg.domaininterface.port.out.AlertEmailPort;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Sending an alert through the mail service.
 *
 * <p>This is the transport every alert in the service goes out on, and until now the port had no
 * implementation at all — the publisher was fully tested against an interface nothing answered.
 */
class RestEmailReferentialClientTest {

  private RestTemplate restTemplate;
  private RestEmailReferentialClient client;

  @BeforeEach
  void setUp() {
    restTemplate = mock(RestTemplate.class);
    client = new RestEmailReferentialClient(
        restTemplate,
        new ReferentialProperties("https://parties", "https://fees", "https://docs",
            "https://referential/mail", "https://common"),
        "invoice-service@example.internal");
  }

  private void accepts() {
    when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(), eq(String.class)))
        .thenReturn(ResponseEntity.ok("sent"));
  }

  @SuppressWarnings("unchecked")
  private List<HttpEntity<MailRequest>> capturedBodies() {
    ArgumentCaptor<HttpEntity<MailRequest>> body = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate, org.mockito.Mockito.atLeastOnce())
        .exchange(any(URI.class), eq(HttpMethod.POST), body.capture(), eq(String.class));
    return body.getAllValues();
  }

  @Test
  @DisplayName("the message is posted to the mail endpoint as JSON")
  void postsToTheMailEndpoint() {
    accepts();

    client.send(new EmailMessage(List.of("ops@example.com"), "subject", "body"));

    ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
    verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.POST), any(), eq(String.class));
    assertEquals("https://referential/mail/email-service/send-mail", uri.getValue().toString());

    HttpEntity<MailRequest> entity = capturedBodies().get(0);
    assertEquals(MediaType.APPLICATION_JSON, entity.getHeaders().getContentType());

    MailRequest sent = entity.getBody();
    assertEquals("subject", sent.getMailSubject());
    assertEquals("body", sent.getMailBody());
    assertEquals("ops@example.com", sent.getToAddress());
    assertEquals("invoice-service@example.internal", sent.getFromAddress(),
        "the configured sender, so a production alert is not mistaken for a test one");
  }

  @Test
  @DisplayName("each recipient gets its own request")
  void oneRequestPerRecipient() {
    // MailRequest.toAddress is singular. Joining the list on a separator this endpoint may not
    // split would produce one message nobody receives; a message each is the failure-safe read.
    accepts();

    client.send(new EmailMessage(
        List.of("a@example.com", "b@example.com", "c@example.com"), "subject", "body"));

    verify(restTemplate, times(3))
        .exchange(any(URI.class), eq(HttpMethod.POST), any(), eq(String.class));
    assertEquals(List.of("a@example.com", "b@example.com", "c@example.com"),
        capturedBodies().stream().map(e -> e.getBody().getToAddress()).toList());
  }

  @Test
  @DisplayName("a transport failure is translated, naming the recipient that failed")
  void transportFailureIsTranslated() {
    when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(), eq(String.class)))
        .thenThrow(new ResourceAccessException("connect timed out"));

    AlertEmailPort.EmailDispatchException thrown =
        assertThrows(AlertEmailPort.EmailDispatchException.class,
            () -> client.send(new EmailMessage(List.of("ops@example.com"), "subject", "body")));

    assertTrue(thrown.getMessage().contains("ops@example.com"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("subject"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("no recipient was reached"), thrown.getMessage());
  }

  @Test
  @DisplayName("a partial failure says who already received it")
  void partialFailureNamesTheDelivered() {
    // Without this the caller knows only that the send failed, and re-sending to the whole list
    // to be safe means everyone who already got it gets it twice.
    when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(), eq(String.class)))
        .thenReturn(ResponseEntity.ok("sent"))
        .thenThrow(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "down",
            org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));

    AlertEmailPort.EmailDispatchException thrown =
        assertThrows(AlertEmailPort.EmailDispatchException.class,
            () -> client.send(new EmailMessage(
                List.of("first@example.com", "second@example.com"), "subject", "body")));

    assertTrue(thrown.getMessage().contains("second@example.com"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("already delivered to first@example.com"),
        thrown.getMessage());
    assertTrue(thrown.getMessage().contains("1 of 2 sent"), thrown.getMessage());
  }

  @Test
  @DisplayName("collaborators and the message are mandatory")
  void mandatoryArguments() {
    ReferentialProperties props =
        new ReferentialProperties("https://a", "https://b", "https://c", "https://d", "https://common");

    assertThrows(NullPointerException.class,
        () -> new RestEmailReferentialClient(null, props, "from@example.com"));
    assertThrows(NullPointerException.class,
        () -> new RestEmailReferentialClient(restTemplate, null, "from@example.com"));
    assertThrows(NullPointerException.class,
        () -> new RestEmailReferentialClient(restTemplate, props, null));
    assertThrows(NullPointerException.class, () -> client.send(null));
  }
}
