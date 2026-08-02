package com.example.invoice.mapper.einvoice;

import com.example.invoice.mapper.einvoice.model.invoice.AdditionalDocumentReference;
import com.example.invoice.mapper.einvoice.model.invoice.EmbeddedDocument;
import com.example.invoice.mapper.einvoice.model.invoice.Invoice;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Extracts the base64-embedded attachments from an {@link Invoice} as raw byte payloads.
 *
 * <p>Ported from A's {@code MultipartExtractionService}. One shape change vs A on the output
 * side: the original returned Spring {@code MockMultipartFile[]}; this port returns
 * {@link ExtractedAttachment} records ({@code filename / bytes / mimeType}) instead — the
 * enforcer rule on {@code invoice-mapper} bans {@code org.springframework:*}, and the mapper
 * module doesn't need to know about Spring's multipart abstraction. Callers wrap the raw
 * payload in whatever their web-layer expects.
 *
 * <p>Corruption checks run before an attachment is included:
 * <ul>
 *   <li>Base64 must decode without {@link IllegalArgumentException}.</li>
 *   <li>Decoded byte array must be non-empty.</li>
 *   <li>If the filename has a known extension, the magic-byte signature must match
 *       (PDF: {@code %PDF}; XLSX/DOCX/ZIP: {@code PK\003\004}; legacy XLS: {@code D0CF11E0A1B11AE1}).</li>
 * </ul>
 * Anything that fails a check is silently dropped from the {@link #extract(Invoice)} output.
 * Callers that need to surface corruption to the user should call
 * {@link #extractDetailed(Invoice)} and inspect the {@link Result#status()} list.
 */
public class MultipartExtractionService {

  private static final byte[] PDF_MAGIC = new byte[] {0x25, 0x50, 0x44, 0x46}; // %PDF
  private static final byte[] OOXML_ZIP_MAGIC = new byte[] {0x50, 0x4B, 0x03, 0x04}; // PK..
  private static final byte[] LEGACY_OLE_MAGIC =
      new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

  /** Returns only the well-formed attachments; corrupt entries are silently skipped. */
  public List<ExtractedAttachment> extract(Invoice invoice) {
    List<ExtractedAttachment> out = new ArrayList<>();
    for (Result r : extractDetailed(invoice)) {
      if (r.attachment() != null) out.add(r.attachment());
    }
    return out;
  }

  /** Returns one {@link Result} per attachment so the caller can see why something was rejected. */
  public List<Result> extractDetailed(Invoice invoice) {
    List<Result> out = new ArrayList<>();
    if (invoice == null || invoice.getAdditionalDocumentReference() == null) return out;
    for (AdditionalDocumentReference ref : invoice.getAdditionalDocumentReference()) {
      out.add(extractOne(ref));
    }
    return out;
  }

  private Result extractOne(AdditionalDocumentReference ref) {
    if (ref == null || ref.getAttachment() == null
        || ref.getAttachment().getEmbeddedDocumentBinaryObject() == null) {
      return new Result(null, null, Status.MISSING_ATTACHMENT);
    }
    EmbeddedDocument obj = ref.getAttachment().getEmbeddedDocumentBinaryObject();
    String filename = obj.getFilename();
    if (filename == null || filename.isBlank()) {
      return new Result(null, null, Status.MISSING_FILENAME);
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(obj.getFile() == null ? "" : obj.getFile());
    } catch (IllegalArgumentException ex) {
      return new Result(filename, null, Status.BASE64_DECODE_FAILED);
    }
    if (bytes.length == 0) {
      return new Result(filename, null, Status.EMPTY_PAYLOAD);
    }
    if (!matchesSignature(filename, bytes)) {
      return new Result(filename, null, Status.SIGNATURE_MISMATCH);
    }
    String mime = obj.getMimeCode() == null ? guessMime(filename) : obj.getMimeCode();
    return new Result(filename, new ExtractedAttachment(filename, bytes, mime), Status.OK);
  }

  private static boolean matchesSignature(String filename, byte[] bytes) {
    String lower = filename.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".pdf")) return startsWith(bytes, PDF_MAGIC);
    if (lower.endsWith(".xlsx") || lower.endsWith(".docx") || lower.endsWith(".zip")) {
      return startsWith(bytes, OOXML_ZIP_MAGIC);
    }
    if (lower.endsWith(".xls") || lower.endsWith(".doc")) {
      return startsWith(bytes, LEGACY_OLE_MAGIC);
    }
    // Unknown extension — accept by default rather than reject.
    return true;
  }

  private static boolean startsWith(byte[] haystack, byte[] needle) {
    if (haystack.length < needle.length) return false;
    for (int i = 0; i < needle.length; i++) {
      if (haystack[i] != needle[i]) return false;
    }
    return true;
  }

  private static String guessMime(String filename) {
    String lower = filename.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".pdf")) return "application/pdf";
    if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
    if (lower.endsWith(".xlsx"))
      return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    if (lower.endsWith(".csv")) return "text/csv";
    return "application/octet-stream";
  }

  public enum Status {
    OK,
    MISSING_ATTACHMENT,
    MISSING_FILENAME,
    BASE64_DECODE_FAILED,
    EMPTY_PAYLOAD,
    SIGNATURE_MISMATCH
  }

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
  public record Result(String filename, ExtractedAttachment attachment, Status status) {}
}
