package com.example.invoice.service.registration.error;

import java.time.Instant;
import java.util.Objects;

/**
 * One failure captured during registration.
 *
 * <p>Carries the taxonomy hit ({@link ErrorCode}), a human-readable detail message pinned to
 * the specific occurrence (usually the exception message or a field value that failed a rule),
 * the {@link Throwable cause} when one is available, and the timestamp when the failure was
 * detected. Immutable; the registration service accumulates a {@code List<MappingError>} and
 * hands the list to both the persistence layer (JSON-serialised) and the alert notifier.
 */
public record MappingError(ErrorCode code, String detail, Throwable cause, Instant detectedAt) {

  public MappingError {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(detail, "detail");
    if (detectedAt == null) detectedAt = Instant.now();
  }

  /** Convenience for the common case of "no cause, just a detail message". */
  public static MappingError of(ErrorCode code, String detail) {
    return new MappingError(code, detail, null, Instant.now());
  }

  /** Convenience for wrapping an exception. */
  public static MappingError of(ErrorCode code, String detail, Throwable cause) {
    return new MappingError(code, detail, cause, Instant.now());
  }
}
