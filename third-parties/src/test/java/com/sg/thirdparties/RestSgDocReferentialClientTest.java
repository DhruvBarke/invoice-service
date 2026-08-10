package com.sg.thirdparties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sg.domaininterface.model.invoice.ExtractedAttachment;
import com.sg.domaininterface.port.thirdparty.ReferentialUnavailableException;
import com.sg.thirdparties.RestSgDocReferentialClient.UploadResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Storing and fetching document content.
 *
 * <p>The handle is the whole point: {@code t_invoice_document_payable} keeps metadata and an
 * {@code sg_doc_id}, so an upload that comes back without one has stored nothing findable and
 * must not be recorded as though it succeeded.
 */
class RestSgDocReferentialClientTest {

  private RestTemplate restTemplate;
  private RestSgDocReferentialClient client;

  @BeforeEach
  void setUp() {
    restTemplate = mock(RestTemplate.class);
    client = new RestSgDocReferentialClient(restTemplate,
        new ReferentialProperties("https://parties", "https://fees", "https://referential/docs", "https://mail"));
  }

  private static ExtractedAttachment pdf() {
    return new ExtractedAttachment("invoice.pdf",
        "%PDF-1.4 content".getBytes(StandardCharsets.UTF_8), "application/pdf");
  }

  // ── Upload ────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("upload")
  class Upload {

    @Test
    @DisplayName("the bytes go up as a named file part with the invoice reference alongside")
    void uploadsMultipart() {
      when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(),
          eq(UploadResponse.class))).thenReturn(ResponseEntity.ok(new UploadResponse("DOC-1")));

      assertEquals("DOC-1", client.upload(pdf(), "0001000042"));

      ArgumentCaptor<HttpEntity<MultiValueMap<String, Object>>> body =
          ArgumentCaptor.forClass(HttpEntity.class);
      ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
      verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.POST), body.capture(),
          eq(UploadResponse.class));

      assertEquals("https://referential/docs/documents", uri.getValue().toString());
      assertEquals(MediaType.MULTIPART_FORM_DATA, body.getValue().getHeaders().getContentType());

      MultiValueMap<String, Object> parts = body.getValue().getBody();
      Resource file = (Resource) parts.getFirst("file");
      // Without a filename the store records the document as unnamed, which makes it
      // unidentifiable in a document list.
      assertEquals("invoice.pdf", file.getFilename());
      assertEquals("0001000042", parts.getFirst("invoiceReference"));
    }

    @Test
    @DisplayName("an absent invoice reference simply omits that part")
    void referenceIsOptional() {
      when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(),
          eq(UploadResponse.class))).thenReturn(ResponseEntity.ok(new UploadResponse("DOC-1")));

      client.upload(pdf(), null);

      ArgumentCaptor<HttpEntity<MultiValueMap<String, Object>>> body =
          ArgumentCaptor.forClass(HttpEntity.class);
      verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.POST), body.capture(),
          eq(UploadResponse.class));
      assertFalse(body.getValue().getBody().containsKey("invoiceReference"));
    }

    @Test
    @DisplayName("an empty file is refused without a round trip")
    void emptyContentIsRefused() {
      // Uploading nothing would return a handle to an empty document, which reads downstream as
      // a document that exists.
      ExtractedAttachment empty = new ExtractedAttachment("blank.pdf", new byte[0], "application/pdf");

      ReferentialUnavailableException thrown = assertThrows(ReferentialUnavailableException.class,
          () -> client.upload(empty, "0001000042"));

      assertFalse(thrown.isRetryable(), "the file will be just as empty next time");
      verify(restTemplate, never()).exchange(any(URI.class), any(), any(), eq(UploadResponse.class));
    }

    @Test
    @DisplayName("null content is refused too")
    void nullContentIsRefused() {
      ExtractedAttachment none = new ExtractedAttachment("blank.pdf", null, "application/pdf");
      assertThrows(ReferentialUnavailableException.class, () -> client.upload(none, "REF"));
    }

    @Test
    @DisplayName("a response with no handle is a failure")
    void missingHandleIsAFailure() {
      // Recording a null handle as success leaves a document row that looks complete and points
      // at nothing.
      when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(),
          eq(UploadResponse.class)))
          .thenReturn(ResponseEntity.ok(new UploadResponse(null)), ResponseEntity.ok(null));

      assertThrows(ReferentialUnavailableException.class, () -> client.upload(pdf(), "REF"));
      assertThrows(ReferentialUnavailableException.class, () -> client.upload(pdf(), "REF"));
    }

    @Test
    @DisplayName("a blank handle is treated as no handle")
    void blankHandleIsAFailure() {
      when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(),
          eq(UploadResponse.class))).thenReturn(ResponseEntity.ok(new UploadResponse("   ")));

      assertThrows(ReferentialUnavailableException.class, () -> client.upload(pdf(), "REF"));
    }

    @Test
    @DisplayName("the failure names the file, and a 5xx is retryable")
    void failuresNameTheFile() {
      when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(),
          eq(UploadResponse.class)))
          .thenThrow(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "down",
              HttpHeaders.EMPTY, new byte[0], null));

      ReferentialUnavailableException thrown = assertThrows(ReferentialUnavailableException.class,
          () -> client.upload(pdf(), "REF"));

      assertTrue(thrown.getMessage().contains("invoice.pdf"));
      assertTrue(thrown.isRetryable());
      assertEquals("sgdoc", thrown.referential());
    }

    @Test
    @DisplayName("a 4xx is not retryable")
    void clientErrorIsNotRetryable() {
      when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(),
          eq(UploadResponse.class)))
          .thenThrow(HttpClientErrorException.create(HttpStatus.PAYLOAD_TOO_LARGE, "too big",
              HttpHeaders.EMPTY, new byte[0], null));

      assertFalse(assertThrows(ReferentialUnavailableException.class,
          () -> client.upload(pdf(), "REF")).isRetryable());
    }

    @Test
    @DisplayName("no response at all is retryable")
    void noResponseIsRetryable() {
      when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(),
          eq(UploadResponse.class))).thenThrow(new ResourceAccessException("timeout"));

      assertTrue(assertThrows(ReferentialUnavailableException.class,
          () -> client.upload(pdf(), "REF")).isRetryable());
    }

    @Test
    @DisplayName("the attachment is mandatory")
    void attachmentIsMandatory() {
      assertThrows(NullPointerException.class, () -> client.upload(null, "REF"));
    }
  }

  // ── Download ──────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("download")
  class Download {

    private void answerWith(byte[] content, HttpHeaders headers) {
      when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(byte[].class)))
          .thenReturn(new ResponseEntity<>(content, headers, HttpStatus.OK));
    }

    @Test
    @DisplayName("the content comes back with the store's own name and type")
    void returnsContent() {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentDisposition(
          ContentDisposition.attachment().filename("statement.pdf").build());
      headers.setContentType(MediaType.APPLICATION_PDF);
      answerWith(new byte[] {1, 2, 3}, headers);

      ExtractedAttachment fetched = client.download("DOC-1");

      assertEquals("statement.pdf", fetched.filename());
      assertEquals(MediaType.APPLICATION_PDF_VALUE, fetched.mimeType());
      assertArrayEquals(new byte[] {1, 2, 3}, fetched.bytes());
    }

    @Test
    @DisplayName("a store that does not name the document falls back to the handle")
    void fallsBackToTheHandle() {
      // Better an identifiable name than a null one: the handle at least says which document.
      answerWith(new byte[] {1}, new HttpHeaders());

      ExtractedAttachment fetched = client.download("DOC-1");

      assertEquals("DOC-1", fetched.filename());
      assertEquals(MediaType.APPLICATION_OCTET_STREAM_VALUE, fetched.mimeType());
    }

    @Test
    @DisplayName("a blank declared filename falls back to the handle as well")
    void blankFilenameFallsBack() {
      // A Content-Disposition with an empty filename is a header the store did fill in, badly.
      // Taking it literally would record the document under the empty name, which is worse than
      // no header at all because it looks deliberate.
      HttpHeaders headers = new HttpHeaders();
      headers.setContentDisposition(ContentDisposition.attachment().filename("   ").build());
      answerWith(new byte[] {1}, headers);

      assertEquals("DOC-1", client.download("DOC-1").filename());
    }

    @Test
    @DisplayName("an empty or absent body is a failure")
    void emptyBodyIsAFailure() {
      answerWith(new byte[0], new HttpHeaders());
      assertThrows(ReferentialUnavailableException.class, () -> client.download("DOC-1"));

      when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(byte[].class)))
          .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));
      assertThrows(ReferentialUnavailableException.class, () -> client.download("DOC-1"));
    }

    @Test
    @DisplayName("a missing handle is refused without a round trip")
    void missingHandleIsRefused() {
      assertThrows(ReferentialUnavailableException.class, () -> client.download(null));
      assertThrows(ReferentialUnavailableException.class, () -> client.download("  "));
      verify(restTemplate, never()).exchange(any(URI.class), any(), any(), eq(byte[].class));
    }

    @Test
    @DisplayName("transport failures are translated and carry the handle")
    void failuresAreTranslated() {
      when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(byte[].class)))
          .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "gone",
              HttpHeaders.EMPTY, new byte[0], null));

      ReferentialUnavailableException thrown = assertThrows(ReferentialUnavailableException.class,
          () -> client.download("DOC-1"));
      assertTrue(thrown.getMessage().contains("DOC-1"));
      assertFalse(thrown.isRetryable());

      when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(byte[].class)))
          .thenThrow(new ResourceAccessException("timeout"));
      assertTrue(assertThrows(ReferentialUnavailableException.class,
          () -> client.download("DOC-1")).isRetryable());
    }
  }

  @Test
  @DisplayName("collaborators are mandatory")
  void mandatoryCollaborators() {
    ReferentialProperties props = new ReferentialProperties("https://a", "https://b", "https://c", "https://mail");
    assertThrows(NullPointerException.class, () -> new RestSgDocReferentialClient(null, props));
    assertThrows(NullPointerException.class,
        () -> new RestSgDocReferentialClient(restTemplate, null));
  }
}
