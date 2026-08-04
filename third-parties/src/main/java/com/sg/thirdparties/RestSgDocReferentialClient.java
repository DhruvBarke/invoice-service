package com.sg.thirdparties;

import com.sg.domaininterface.model.invoice.ExtractedAttachment;
import com.sg.domaininterface.port.thirdparty.ReferentialUnavailableException;
import com.sg.domaininterface.port.thirdparty.SgDocReferentialService;
import java.net.URI;
import java.util.Objects;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * {@link SgDocReferentialService} over the document store's HTTP API.
 *
 * <p>Upload is multipart: the bytes as a file part, the invoice reference as a form field so the
 * document is findable from the invoice without a second index. The response carries the handle
 * that goes into {@code t_invoice_document_payable.sg_doc_id}.
 *
 * <p><b>An upload that returns no handle is a failure.</b> It stored nothing findable, and
 * recording a null handle as though it had succeeded would leave a document row that looks
 * complete and points at nothing.
 */
public final class RestSgDocReferentialClient implements SgDocReferentialService {

  private static final String REFERENTIAL = "sgdoc";

  /** The upload response. Only the handle is used; the rest of the payload is ignored. */
  public record UploadResponse(String sgDocId) {}

  private final RestTemplate restTemplate;
  private final ReferentialProperties properties;

  public RestSgDocReferentialClient(RestTemplate restTemplate, ReferentialProperties properties) {
    this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  @Override
  public String upload(ExtractedAttachment attachment, String invoiceReference) {
    Objects.requireNonNull(attachment, "attachment");
    if (attachment.bytes() == null || attachment.bytes().length == 0) {
      // Uploading nothing would return a handle to an empty document, which reads downstream as
      // a document that exists. Refusing here keeps "no content" from becoming "content we
      // cannot explain".
      throw new ReferentialUnavailableException(REFERENTIAL,
          "refusing to upload '" + attachment.filename() + "': no content", false, null);
    }

    URI uri = UriComponentsBuilder
        .fromUriString(properties.sgDocBaseUrl() + "/documents")
        .build().encode().toUri();

    try {
      ResponseEntity<UploadResponse> response = restTemplate.exchange(
          uri, HttpMethod.POST, multipartBody(attachment, invoiceReference), UploadResponse.class);

      UploadResponse body = response.getBody();
      if (body == null || body.sgDocId() == null || body.sgDocId().isBlank()) {
        throw new ReferentialUnavailableException(REFERENTIAL,
            "upload of '" + attachment.filename() + "' returned no document id", true, null);
      }
      return body.sgDocId();

    } catch (HttpStatusCodeException e) {
      throw new ReferentialUnavailableException(REFERENTIAL,
          "upload of '" + attachment.filename() + "' returned " + e.getStatusCode().value(),
          e.getStatusCode().is5xxServerError(), e);
    } catch (RestClientException e) {
      throw new ReferentialUnavailableException(REFERENTIAL,
          "sgdoc unreachable while uploading '" + attachment.filename() + "': " + e.getMessage(),
          true, e);
    }
  }

  @Override
  public ExtractedAttachment download(String sgDocId) {
    if (sgDocId == null || sgDocId.isBlank()) {
      throw new ReferentialUnavailableException(REFERENTIAL,
          "cannot fetch a document without a handle", false, null);
    }

    URI uri = UriComponentsBuilder
        .fromUriString(properties.sgDocBaseUrl() + "/documents/{id}")
        .build(sgDocId);

    try {
      ResponseEntity<byte[]> response =
          restTemplate.exchange(uri, HttpMethod.GET, null, byte[].class);

      byte[] content = response.getBody();
      if (content == null || content.length == 0) {
        throw new ReferentialUnavailableException(REFERENTIAL,
            "document " + sgDocId + " came back empty", true, null);
      }
      return new ExtractedAttachment(filenameOf(response.getHeaders(), sgDocId), content,
          contentTypeOf(response.getHeaders()));

    } catch (HttpStatusCodeException e) {
      throw new ReferentialUnavailableException(REFERENTIAL,
          "fetch of " + sgDocId + " returned " + e.getStatusCode().value(),
          e.getStatusCode().is5xxServerError(), e);
    } catch (RestClientException e) {
      throw new ReferentialUnavailableException(REFERENTIAL,
          "sgdoc unreachable while fetching " + sgDocId + ": " + e.getMessage(), true, e);
    }
  }

  /** The bytes as a file part, plus the invoice reference as a form field. */
  private static HttpEntity<MultiValueMap<String, Object>> multipartBody(
      ExtractedAttachment attachment, String invoiceReference) {

    ByteArrayResource content = new ByteArrayResource(attachment.bytes()) {
      @Override
      public String getFilename() {
        // Without this the resource has no filename and the store records the document as
        // unnamed, which makes it unidentifiable in the document list.
        return attachment.filename();
      }
    };

    MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    parts.add("file", content);
    if (invoiceReference != null) {
      parts.add("invoiceReference", invoiceReference);
    }

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    return new HttpEntity<>(parts, headers);
  }

  /** The store's name for the document, falling back to the handle when it does not say. */
  private static String filenameOf(HttpHeaders headers, String sgDocId) {
    String declared = headers.getContentDisposition().getFilename();
    return declared == null || declared.isBlank() ? sgDocId : declared;
  }

  private static String contentTypeOf(HttpHeaders headers) {
    MediaType type = headers.getContentType();
    return type == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : type.toString();
  }
}
