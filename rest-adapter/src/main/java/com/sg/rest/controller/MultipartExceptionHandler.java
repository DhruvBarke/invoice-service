package com.sg.rest.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * Turns multipart transport failures into the statuses the API documents.
 *
 * <p>Without this, both cases below reach Spring Boot's default error handling and come back as
 * 500. That is actively misleading: 500 is the one class of response this API tells callers to
 * retry, and retrying an upload that was rejected for being too large just sends the same
 * oversized file again. The spec documented a 413 that the service could not actually produce.
 *
 * <p><b>Neither of these is a registration outcome.</b> They happen before the invoice is read, so
 * nothing was stored and there is no verdict to report — which is why the body is a
 * {@link ProblemDetail} rather than a {@code RegistrationOutcome}. Returning an outcome here would
 * imply a row exists.
 */
@RestControllerAdvice
public class MultipartExceptionHandler {

  /**
   * The upload exceeded the configured limit.
   *
   * <p>Matched ahead of {@link #malformedMultipart} because Spring dispatches to the most specific
   * handler, and {@link MaxUploadSizeExceededException} extends {@link MultipartException}.
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ProblemDetail uploadTooLarge(MaxUploadSizeExceededException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.PAYLOAD_TOO_LARGE,
        "An uploaded file exceeded the configured limit. Nothing was registered. Resending the "
            + "same file will fail the same way — split the attachment or ask for the limit to "
            + "be raised.");
    problem.setTitle("Upload too large");
    // The configured maximum, so the sender learns what to aim at instead of guessing. -1 when
    // the container did not report one, which is why it is only set when it is meaningful.
    if (e.getMaxUploadSize() > 0) {
      problem.setProperty("maxUploadSizeBytes", e.getMaxUploadSize());
    }
    return problem;
  }

  /**
   * The request was not readable as multipart at all — a truncated upload, or a malformed
   * boundary.
   *
   * <p>400 rather than 500: the request is what is wrong, and the caller is the only one who can
   * fix it. Sending it again unchanged will fail identically.
   */
  @ExceptionHandler(MultipartException.class)
  public ProblemDetail malformedMultipart(MultipartException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST,
        "The request could not be read as multipart/form-data. Nothing was registered. "
            + "Check that the 'invoice' part is present and that the upload completed.");
    problem.setTitle("Malformed multipart request");
    return problem;
  }
}
