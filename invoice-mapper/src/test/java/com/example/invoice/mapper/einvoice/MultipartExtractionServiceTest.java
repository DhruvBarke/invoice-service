package com.example.invoice.mapper.einvoice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.invoice.mapper.einvoice.DocumentReferenceMapper.AttachmentPayload;
import com.example.invoice.mapper.einvoice.MultipartExtractionService.ExtractedAttachment;
import com.example.invoice.mapper.einvoice.MultipartExtractionService.Result;
import com.example.invoice.mapper.einvoice.MultipartExtractionService.Status;
import com.example.invoice.mapper.einvoice.model.invoice.AdditionalDocumentReference;
import com.example.invoice.mapper.einvoice.model.invoice.Attachment;
import com.example.invoice.mapper.einvoice.model.invoice.EmbeddedDocument;
import com.example.invoice.mapper.einvoice.model.invoice.Invoice;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Attachment extraction and its corruption checks — the gate that decides whether a file ever
 * reaches the registration pipeline.
 */
class MultipartExtractionServiceTest {

  private final MultipartExtractionService service = new MultipartExtractionService();

  private static final byte[] PDF = "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
  private static final byte[] OOXML = {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00};
  private static final byte[] LEGACY_OLE =
      {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

  private static Invoice invoiceWith(AdditionalDocumentReference... refs) {
    Invoice inv = new Invoice();
    inv.setAdditionalDocumentReference(new ArrayList<>(List.of(refs)));
    return inv;
  }

  private static AdditionalDocumentReference ref(String filename, String mime, byte[] content) {
    EmbeddedDocument doc = new EmbeddedDocument();
    doc.setFilename(filename);
    doc.setMimeCode(mime);
    doc.setFile(content == null ? null : Base64.getEncoder().encodeToString(content));
    Attachment att = new Attachment();
    att.setEmbeddedDocumentBinaryObject(doc);
    AdditionalDocumentReference r = new AdditionalDocumentReference();
    r.setAttachment(att);
    return r;
  }

  private static AdditionalDocumentReference rawRef(String filename, String base64) {
    EmbeddedDocument doc = new EmbeddedDocument();
    doc.setFilename(filename);
    doc.setFile(base64);
    Attachment att = new Attachment();
    att.setEmbeddedDocumentBinaryObject(doc);
    AdditionalDocumentReference r = new AdditionalDocumentReference();
    r.setAttachment(att);
    return r;
  }

  // ── Happy path ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("well-formed attachments")
  class Accepted {

    @Test
    @DisplayName("a valid PDF is decoded with its filename and mime type")
    void validPdfIsExtracted() {
      List<ExtractedAttachment> out =
          service.extract(invoiceWith(ref("invoice.pdf", "application/pdf", PDF)));

      assertEquals(1, out.size());
      assertEquals("invoice.pdf", out.get(0).filename());
      assertEquals("application/pdf", out.get(0).mimeType());
      assertArrayEquals(PDF, out.get(0).bytes());
    }

    @ParameterizedTest(name = "{0} is accepted")
    @CsvSource({"trades.xlsx", "report.docx", "bundle.zip"})
    @DisplayName("OOXML containers are accepted on the PK signature")
    void ooxmlAccepted(String filename) {
      assertEquals(1, service.extract(invoiceWith(ref(filename, null, OOXML))).size());
    }

    @ParameterizedTest(name = "{0} is accepted")
    @CsvSource({"legacy.xls", "legacy.doc"})
    @DisplayName("legacy OLE documents are accepted on their own signature")
    void legacyOleAccepted(String filename) {
      assertEquals(1, service.extract(invoiceWith(ref(filename, null, LEGACY_OLE))).size());
    }

    @Test
    @DisplayName("an unknown extension is accepted rather than rejected on a guess")
    void unknownExtensionAccepted() {
      byte[] content = "tradeId,qty\nT1,100\n".getBytes(StandardCharsets.UTF_8);
      assertEquals(1, service.extract(invoiceWith(ref("trades.csv", "text/csv", content))).size());
    }

    @ParameterizedTest(name = "{0} yields mime {1}")
    @CsvSource({
        "invoice.pdf,  application/pdf",
        "legacy.xls,   application/vnd.ms-excel",
        "trades.xlsx,  application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "trades.csv,   text/csv",
        "notes.bin,    application/octet-stream",
    })
    @DisplayName("a missing mime type is inferred from the extension")
    void mimeIsInferred(String filename, String expectedMime) {
      byte[] content = filename.endsWith(".pdf") ? PDF
          : filename.endsWith(".xlsx") ? OOXML
          : filename.endsWith(".xls") ? LEGACY_OLE
          : "data".getBytes(StandardCharsets.UTF_8);

      List<ExtractedAttachment> out = service.extract(invoiceWith(ref(filename, null, content)));
      assertEquals(expectedMime, out.get(0).mimeType());
    }

    @Test
    @DisplayName("several attachments are all returned, in order")
    void severalAttachments() {
      List<ExtractedAttachment> out = service.extract(invoiceWith(
          ref("invoice.pdf", "application/pdf", PDF),
          ref("trades.xlsx", null, OOXML)));

      assertEquals(2, out.size());
      assertEquals("invoice.pdf", out.get(0).filename());
      assertEquals("trades.xlsx", out.get(1).filename());
    }
  }

  // ── Rejection ─────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("corruption checks")
  class Rejected {

    @Test
    @DisplayName("a PDF whose bytes are not a PDF is dropped")
    void signatureMismatchIsDropped() {
      Invoice inv = invoiceWith(
          ref("corrupt.pdf", "application/pdf", "not a pdf".getBytes(StandardCharsets.UTF_8)));

      assertTrue(service.extract(inv).isEmpty());
      assertEquals(Status.SIGNATURE_MISMATCH, service.extractDetailed(inv).get(0).status());
    }

    @Test
    @DisplayName("an xlsx that is not a zip is dropped")
    void ooxmlSignatureMismatch() {
      Invoice inv = invoiceWith(ref("fake.xlsx", null, PDF));
      assertEquals(Status.SIGNATURE_MISMATCH, service.extractDetailed(inv).get(0).status());
    }

    @Test
    @DisplayName("a legacy xls that is not OLE is dropped")
    void legacySignatureMismatch() {
      Invoice inv = invoiceWith(ref("fake.xls", null, PDF));
      assertEquals(Status.SIGNATURE_MISMATCH, service.extractDetailed(inv).get(0).status());
    }

    @Test
    @DisplayName("a file shorter than its own signature is dropped")
    void truncatedFileIsDropped() {
      Invoice inv = invoiceWith(ref("tiny.pdf", null, new byte[] {0x25, 0x50}));
      assertEquals(Status.SIGNATURE_MISMATCH, service.extractDetailed(inv).get(0).status());
    }

    @Test
    @DisplayName("undecodable base64 is dropped rather than throwing")
    void badBase64IsDropped() {
      Invoice inv = invoiceWith(rawRef("invoice.pdf", "!!!! not base64 !!!!"));
      Result r = service.extractDetailed(inv).get(0);
      assertEquals(Status.BASE64_DECODE_FAILED, r.status());
      assertNull(r.attachment());
      assertTrue(service.extract(inv).isEmpty());
    }

    @Test
    @DisplayName("an empty payload is dropped")
    void emptyPayloadIsDropped() {
      assertEquals(Status.EMPTY_PAYLOAD,
          service.extractDetailed(invoiceWith(ref("invoice.pdf", null, new byte[0])))
              .get(0).status());
    }

    @Test
    @DisplayName("a null file body decodes to empty and is dropped")
    void nullBodyIsDropped() {
      assertEquals(Status.EMPTY_PAYLOAD,
          service.extractDetailed(invoiceWith(rawRef("invoice.pdf", null))).get(0).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("a blank filename is dropped — there is nothing to store it under")
    void blankFilenameIsDropped(String filename) {
      assertEquals(Status.MISSING_FILENAME,
          service.extractDetailed(invoiceWith(ref(filename, null, PDF))).get(0).status());
    }

    @Test
    @DisplayName("a null filename is dropped")
    void nullFilenameIsDropped() {
      assertEquals(Status.MISSING_FILENAME,
          service.extractDetailed(invoiceWith(ref(null, null, PDF))).get(0).status());
    }

    @Test
    @DisplayName("a reference with no attachment at all is reported, not skipped silently")
    void missingAttachmentIsReported() {
      assertEquals(Status.MISSING_ATTACHMENT,
          service.extractDetailed(invoiceWith(new AdditionalDocumentReference()))
              .get(0).status());

      AdditionalDocumentReference emptyAttachment = new AdditionalDocumentReference();
      emptyAttachment.setAttachment(new Attachment());
      assertEquals(Status.MISSING_ATTACHMENT,
          service.extractDetailed(invoiceWith(emptyAttachment)).get(0).status());
    }

    @Test
    @DisplayName("a null entry in the list is reported rather than throwing")
    void nullReferenceIsReported() {
      Invoice inv = new Invoice();
      List<AdditionalDocumentReference> refs = new ArrayList<>();
      refs.add(null);
      inv.setAdditionalDocumentReference(refs);

      assertEquals(Status.MISSING_ATTACHMENT, service.extractDetailed(inv).get(0).status());
      assertTrue(service.extract(inv).isEmpty());
    }

    @Test
    @DisplayName("the good files survive alongside the dropped ones")
    void goodAndBadAreSeparated() {
      Invoice inv = invoiceWith(
          ref("good.pdf", "application/pdf", PDF),
          ref("corrupt.pdf", "application/pdf", "junk".getBytes(StandardCharsets.UTF_8)));

      assertEquals(1, service.extract(inv).size(), "one good file survives");
      assertEquals(2, service.extractDetailed(inv).size(), "both are reported in detail");
      assertEquals(Status.OK, service.extractDetailed(inv).get(0).status());
      assertEquals(Status.SIGNATURE_MISMATCH, service.extractDetailed(inv).get(1).status());
    }
  }

  // ── Absent input ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("absent input")
  class Absent {

    @Test
    @DisplayName("a null invoice yields nothing")
    void nullInvoice() {
      assertTrue(service.extract(null).isEmpty());
      assertTrue(service.extractDetailed(null).isEmpty());
    }

    @Test
    @DisplayName("an invoice with no attachment block yields nothing")
    void noAttachmentBlock() {
      assertTrue(service.extract(new Invoice()).isEmpty());
    }

    @Test
    @DisplayName("an empty attachment list yields nothing")
    void emptyAttachmentList() {
      Invoice inv = new Invoice();
      inv.setAdditionalDocumentReference(new ArrayList<>());
      assertTrue(service.extract(inv).isEmpty());
    }
  }

  // ── Value semantics ───────────────────────────────────────────────────────

  @Nested
  @DisplayName("attachment value types")
  class ValueTypes {

    @Test
    @DisplayName("two attachments with identical content are equal")
    void contentEquality() {
      ExtractedAttachment a = new ExtractedAttachment("f.pdf", PDF.clone(), "application/pdf");
      ExtractedAttachment b = new ExtractedAttachment("f.pdf", PDF.clone(), "application/pdf");

      assertEquals(a, b, "a record's generated equals would compare the arrays by reference");
      assertEquals(a.hashCode(), b.hashCode());
      assertEquals(a, a);
    }

    @Test
    @DisplayName("differing content, name or type makes them unequal")
    void inequality() {
      ExtractedAttachment base = new ExtractedAttachment("f.pdf", PDF, "application/pdf");
      assertNotEquals(base, new ExtractedAttachment("other.pdf", PDF, "application/pdf"));
      assertNotEquals(base, new ExtractedAttachment("f.pdf", OOXML, "application/pdf"));
      assertNotEquals(base, new ExtractedAttachment("f.pdf", PDF, "text/csv"));
      assertNotEquals(base, "not an attachment");
      assertNotEquals(base, null);
    }

    @Test
    @DisplayName("toString reports the size rather than dumping the content")
    void toStringDoesNotDumpContent() {
      String s = new ExtractedAttachment("f.pdf", PDF, "application/pdf").toString();
      assertTrue(s.contains("f.pdf"));
      assertTrue(s.contains(PDF.length + " byte(s)"));
      assertFalse(s.contains("%PDF"), "an accidental log line must not dump a document");

      assertTrue(new ExtractedAttachment("f.pdf", null, "application/pdf")
          .toString().contains("0 byte(s)"));
    }

    @Test
    @DisplayName("the outbound payload has the same value semantics")
    void payloadValueSemantics() {
      AttachmentPayload a = new AttachmentPayload("id-1", PDF.clone(), "f.pdf", "application/pdf");
      AttachmentPayload b = new AttachmentPayload("id-1", PDF.clone(), "f.pdf", "application/pdf");

      assertEquals(a, b);
      assertEquals(a.hashCode(), b.hashCode());
      assertEquals(a, a);
      assertNotEquals(a, new AttachmentPayload("id-2", PDF, "f.pdf", "application/pdf"));
      assertNotEquals(a, new AttachmentPayload("id-1", OOXML, "f.pdf", "application/pdf"));
      assertNotEquals(a, new AttachmentPayload("id-1", PDF, "other.pdf", "application/pdf"));
      assertNotEquals(a, new AttachmentPayload("id-1", PDF, "f.pdf", "text/csv"));
      assertNotEquals(a, "not a payload");
      assertNotEquals(a, null);
      assertTrue(a.toString().contains("id-1"));
    }

    @Test
    @DisplayName("a payload needs an id and a non-null body")
    void payloadValidation() {
      assertThrows(IllegalArgumentException.class,
          () -> new AttachmentPayload(null, PDF, "f.pdf", "application/pdf"));
      assertThrows(IllegalArgumentException.class,
          () -> new AttachmentPayload("  ", PDF, "f.pdf", "application/pdf"));
      assertThrows(IllegalArgumentException.class,
          () -> new AttachmentPayload("id-1", null, "f.pdf", "application/pdf"));
      assertEquals(0, new AttachmentPayload("id-1", new byte[0], "f.pdf", null).bytes().length,
          "empty is allowed — only null is not");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OK", "MISSING_ATTACHMENT", "MISSING_FILENAME",
        "BASE64_DECODE_FAILED", "EMPTY_PAYLOAD", "SIGNATURE_MISMATCH"})
    @DisplayName("every status round-trips by name")
    void statusRoundTrip(String name) {
      assertEquals(name, Status.valueOf(name).name());
    }
  }
}
