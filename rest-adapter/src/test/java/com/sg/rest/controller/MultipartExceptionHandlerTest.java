package com.sg.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * That an oversized or malformed upload gets the status the API documents.
 *
 * <p>Without this handler both cases reach Spring Boot's default error handling and come back as
 * 500 — the one status this API tells callers to retry. Retrying an upload rejected for its size
 * just sends the same file again, so the wrong status here turns a client-side mistake into a
 * loop.
 */
class MultipartExceptionHandlerTest {

  private final MultipartExceptionHandler handler = new MultipartExceptionHandler();

  @Test
  @DisplayName("an oversized upload is 413, carrying the limit it exceeded")
  void oversizedUploadIs413() {
    ProblemDetail problem =
        handler.uploadTooLarge(new MaxUploadSizeExceededException(10_485_760L));

    assertEquals(HttpStatus.PAYLOAD_TOO_LARGE.value(), problem.getStatus());
    assertEquals("Upload too large", problem.getTitle());
    assertNotNull(problem.getDetail());

    // Telling the sender the limit is the difference between one corrected resend and a guessing
    // game against a service that only ever says no.
    assertNotNull(problem.getProperties());
    assertEquals(10_485_760L, problem.getProperties().get("maxUploadSizeBytes"));
  }

  @Test
  @DisplayName("the response says not to retry, because a resend fails identically")
  void detailDiscouragesRetry() {
    ProblemDetail problem =
        handler.uploadTooLarge(new MaxUploadSizeExceededException(10_485_760L));

    String detail = problem.getDetail();
    assertTrue(detail.contains("Nothing was registered"), detail);
    assertTrue(detail.contains("same way") || detail.contains("split"), detail);
  }

  @Test
  @DisplayName("an unreported limit is omitted rather than published as -1")
  void unknownLimitIsOmitted() {
    // Some containers do not report the configured maximum. Emitting -1 would have the sender
    // aim at a number that means "unknown", which is worse than saying nothing.
    ProblemDetail problem = handler.uploadTooLarge(new MaxUploadSizeExceededException(-1L));

    assertEquals(HttpStatus.PAYLOAD_TOO_LARGE.value(), problem.getStatus());
    if (problem.getProperties() != null) {
      assertNull(problem.getProperties().get("maxUploadSizeBytes"));
    }
  }

  @Test
  @DisplayName("a malformed multipart body is 400, not 500")
  void malformedMultipartIs400() {
    // The request is what is wrong, and the caller is the only one who can fix it. A 500 would
    // invite a retry of something that cannot succeed.
    ProblemDetail problem =
        handler.malformedMultipart(new MultipartException("boundary not found"));

    assertEquals(HttpStatus.BAD_REQUEST.value(), problem.getStatus());
    assertEquals("Malformed multipart request", problem.getTitle());
    assertTrue(problem.getDetail().contains("Nothing was registered"), problem.getDetail());
    assertFalse(problem.getDetail().isBlank());
  }

  @Test
  @DisplayName("the size case is handled separately from the general one")
  void sizeIsMoreSpecificThanMalformed() {
    // MaxUploadSizeExceededException extends MultipartException, so both handlers would match.
    // Spring dispatches to the most specific — this asserts the two genuinely differ, which is
    // what makes that dispatch worth relying on.
    ProblemDetail tooLarge =
        handler.uploadTooLarge(new MaxUploadSizeExceededException(1L));
    ProblemDetail malformed =
        handler.malformedMultipart(new MultipartException("truncated"));

    assertEquals(HttpStatus.PAYLOAD_TOO_LARGE.value(), tooLarge.getStatus());
    assertEquals(HttpStatus.BAD_REQUEST.value(), malformed.getStatus());
  }
}
