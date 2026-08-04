package com.sg.mapper.einvoice;

import com.sg.domaininterface.model.invoice.AdditionalDocumentReference;
import com.sg.domaininterface.model.invoice.Attachment;
import com.sg.domaininterface.model.invoice.EmbeddedDocument;
import com.sg.domaininterface.model.invoice.SchemeID;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Outbound-only bridge between the payable side's two opaque attachment ids
 * ({@code invoicePdfId}, {@code invoiceExcelId}) and the einvoice side's
 * {@link AdditionalDocumentReference} list with embedded base64 attachments.
 *
 * <p>Ported from A's {@code DocumentReferenceMapper} — was a MapStruct {@code @Mapper}
 * interface; now a {@code final} utility class. Three shape changes vs A:
 *
 * <ul>
 *   <li><b>SgDocV3Client eliminated.</b> A's version took an {@code SgDocV3Client} and fetched
 *       bytes from it. Per the migration decision, the fetching is the caller's responsibility;
 *       this mapper now takes the raw bytes + metadata directly through
 *       {@link AttachmentPayload}. This drops one port from the module surface — see the
 *       package-info for the rationale.</li>
 *   <li>{@code EmbeddedDocumentBinaryObject} (feesone) → {@link EmbeddedDocument} (in-repo);
 *       same {@code mimeCode / filename / file} fields.</li>
 *   <li>{@code AdditionalDocumentReference.id} is typed as {@link SchemeID} rather than String;
 *       wrapped accordingly.</li>
 * </ul>
 *
 * <p>Ordering is preserved: {@code invoicePdfId} always becomes index {@code [0]};
 * {@code invoiceExcelId} (when present) is index {@code [1]}.
 *
 * <p>Inbound (einvoice → Payable): this mapper does NOT save attachments to sgdoc V3.
 * {@link MultipartExtractionService} extracts the base64 payloads into byte arrays (with a
 * corruption check); the registration endpoint then uploads them to sgdoc V3 and writes the
 * returned ids onto the {@code InvoicePayable}.
 */
public final class DocumentReferenceMapper {

  static final String DESC_PREFIX = "PLACEHOLDER — real attachment fetched from sgdoc V3 by id ";

  private DocumentReferenceMapper() {}

  /**
   * Raw payload for one attachment. Callers resolve the bytes (typically by fetching them from
   * their document-store service) and hand a fully-populated record to the outbound mapper.
   *
   * @param id       the id the payable side already stores (UUID string or opaque token)
   * @param bytes    file contents; may be empty but must be non-null
   * @param filename display filename; if null the mapper synthesises {@code id + fallbackExt}
   * @param mimeType MIME type; if null the mapper uses the type-specific fallback
   */
  public record AttachmentPayload(String id, byte[] bytes, String filename, String mimeType) {
    public AttachmentPayload {
      if (id == null || id.isBlank()) {
        throw new IllegalArgumentException("attachment id is required");
      }
      if (bytes == null) {
        throw new IllegalArgumentException("attachment bytes must be non-null (empty is allowed)");
      }
    }

    /** Value semantics over the content — see the note on {@code ExtractedAttachment}. */
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof AttachmentPayload other)) return false;
      return Objects.equals(id, other.id)
          && Objects.equals(filename, other.filename)
          && Objects.equals(mimeType, other.mimeType)
          && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
      return 31 * Objects.hash(id, filename, mimeType) + Arrays.hashCode(bytes);
    }

    /** {@code bytes} is non-null by construction, so the length needs no guard here. */
    @Override
    public String toString() {
      return "AttachmentPayload[id=" + id + ", filename=" + filename
          + ", mimeType=" + mimeType
          + ", bytes=" + bytes.length + " byte(s)]";
    }
  }

  // ── outbound ─────────────────────────────────────────────────────────────

  public static List<AdditionalDocumentReference> toAdditionalDocumentReferences(
      AttachmentPayload pdf, AttachmentPayload excel) {
    List<AdditionalDocumentReference> refs = new ArrayList<>();
    if (pdf != null) {
      refs.add(buildRef(pdf, ".pdf", "application/pdf"));
    }
    if (excel != null) {
      refs.add(buildRef(excel, ".xlsx",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }
    return refs;
  }

  private static AdditionalDocumentReference buildRef(
      AttachmentPayload payload, String fallbackExt, String fallbackMime) {
    AdditionalDocumentReference ref = new AdditionalDocumentReference();
    SchemeID refId = new SchemeID();
    refId.setValue(payload.id());
    ref.setId(refId);

    EmbeddedDocument obj = new EmbeddedDocument();
    obj.setFilename(payload.filename() != null ? payload.filename() : payload.id() + fallbackExt);
    obj.setMimeCode(payload.mimeType() != null ? payload.mimeType() : fallbackMime);
    obj.setFile(Base64.getEncoder().encodeToString(payload.bytes()));

    Attachment att = new Attachment();
    att.setEmbeddedDocumentBinaryObject(obj);
    ref.setAttachment(att);
    ref.setDocumentDescription(DESC_PREFIX + payload.id());
    return ref;
  }

  // Inbound deliberately omitted: see MultipartExtractionService.
}
