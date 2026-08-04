package com.example.invoice.service.domain.model.invoice;

import java.util.Arrays;
import java.util.Objects;

/**
 * Raw extracted attachment. Callers wrap this in whatever their web layer expects
 * (Spring MultipartFile, Jakarta {@code Part}, plain HTTP body, etc.).
 */
public record ExtractedAttachment(String filename, byte[] bytes, String mimeType) {

  /**
   * {@code equals}/{@code hashCode}/{@code toString} are written out explicitly because a
   * record's generated implementations compare an array component by <em>reference</em>,
   * which makes two attachments with identical content unequal — surprising, and the kind of
   * defect that only surfaces in a test asserting on a collection of these.
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ExtractedAttachment other)) return false;
    return Objects.equals(filename, other.filename)
        && Objects.equals(mimeType, other.mimeType)
        && Arrays.equals(bytes, other.bytes);
  }

  @Override
  public int hashCode() {
    return 31 * Objects.hash(filename, mimeType) + Arrays.hashCode(bytes);
  }

  @Override
  public String toString() {
    // Content is deliberately not rendered: attachments are routinely megabytes, and an
    // accidental toString() in a log line should not dump a PDF.
    return "ExtractedAttachment[filename=" + filename
        + ", mimeType=" + mimeType
        + ", bytes=" + (bytes == null ? 0 : bytes.length) + " byte(s)]";
  }
}

/** One per attachment slot. {@code attachment} is null whenever {@code status != OK}. */
