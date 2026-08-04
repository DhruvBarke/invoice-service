package com.sg.rest.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sg.domaininterface.model.invoice.Invoice;
import com.sg.domaininterface.model.invoice.NoteEntry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The wire format for an inbound e-invoice.
 *
 * <p>The interesting part is {@link NoteEntry}. UBL carries a note as a single string with the
 * subject code fused into the front of the text — {@code "#AAI#some remark"} — while the model
 * holds the two apart. Every test here that looks like it is about string handling is really
 * about that: a subject code that survives a round trip is a note that keeps its meaning, and one
 * that does not becomes free text nobody routes on.
 */
class EInvoiceJsonCodecTest {

  // ── Invoice round trip ────────────────────────────────────────────────────

  @Nested
  @DisplayName("the invoice document")
  class Documents {

    @Test
    @DisplayName("a minimal invoice round-trips")
    void roundTrip() {
      Invoice original = new Invoice();
      original.setId("SUP-INV-1");
      original.setIssueDate(LocalDate.of(2026, 1, 15));

      Invoice back = EInvoiceJsonCodec.fromJson(EInvoiceJsonCodec.toJson(original));

      assertEquals("SUP-INV-1", back.getId());
      assertEquals(LocalDate.of(2026, 1, 15), back.getIssueDate());
    }

    @Test
    @DisplayName("dates are ISO strings, not epoch numbers")
    void datesAreIsoStrings() {
      Invoice invoice = new Invoice();
      invoice.setIssueDate(LocalDate.of(2026, 1, 15));

      // A sender reading the payload should see the date they sent, and an epoch number is not
      // a date anyone can check against their own system.
      assertTrue(EInvoiceJsonCodec.toJson(invoice).contains("\"2026-01-15\""));
    }

    @Test
    @DisplayName("nulls are omitted rather than written out")
    void nullsAreOmitted() {
      Invoice invoice = new Invoice();
      invoice.setId("SUP-INV-1");

      String json = EInvoiceJsonCodec.toJson(invoice);

      assertFalse(json.contains("null"),
          "an absent field and a field explicitly set to null mean the same thing here, and "
              + "writing both makes the payload larger without making it clearer");
    }

    @Test
    @DisplayName("an unknown property is ignored rather than rejected")
    void unknownPropertiesAreIgnored() {
      // The sender's schema moves independently of ours. Failing on a field we do not model
      // would refuse invoices over something we had already decided not to care about.
      Invoice back = EInvoiceJsonCodec.fromJson(
          "{\"id\":\"SUP-INV-1\",\"somethingTheSenderAdded\":42}");

      assertEquals("SUP-INV-1", back.getId());
    }

    @Test
    @DisplayName("a single value is accepted where a list is expected")
    void singleValueBecomesAList() {
      // Senders that have exactly one note routinely send it unwrapped.
      Invoice back = EInvoiceJsonCodec.fromJson("{\"id\":\"X\",\"note\":\"#AAI#one remark\"}");

      assertNotNull(back.getNote());
      assertEquals(1, back.getNote().size());
      assertEquals("AAI", back.getNote().get(0).getSubjectCode());
    }

    @Test
    @DisplayName("malformed JSON is rejected with the parser's reason")
    void malformedJsonIsRejected() {
      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> EInvoiceJsonCodec.fromJson("{not json"));

      assertTrue(thrown.getMessage().startsWith("JSON→Invoice failed:"),
          "the message says which direction failed, which is the first thing anyone asks");
      assertNotNull(thrown.getCause());
    }

    @Test
    @DisplayName("the mapper is shared, not rebuilt per call")
    void mapperIsShared() {
      // Building an ObjectMapper is expensive and this one is immutable once configured.
      assertSame(EInvoiceJsonCodec.mapper(), EInvoiceJsonCodec.mapper());
    }

    @Test
    @DisplayName("a value that cannot be serialised reports which direction failed")
    void unserialisableValueIsReported() throws Exception {
      IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
          EInvoiceJsonCodec.toJson(new Invoice() {
            @SuppressWarnings("unused")
            private final Object boom = new Object() {
              @Override public String toString() { return "x"; }
            };

            private void writeObject(java.io.ObjectOutputStream out) {
              throw new UnsupportedOperationException();
            }
          }));

      assertTrue(thrown.getMessage().startsWith("Invoice→JSON failed:"));
    }
  }

  // ── NoteEntry ─────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("notes")
  class Notes {

    private NoteEntry parse(String json) throws Exception {
      return EInvoiceJsonCodec.mapper().readValue(json, NoteEntry.class);
    }

    private String write(NoteEntry note) throws Exception {
      return EInvoiceJsonCodec.mapper().writeValueAsString(note);
    }

    @Test
    @DisplayName("a UBL note splits into subject code and text")
    void ublNoteSplits() throws Exception {
      NoteEntry note = parse("\"#AAI#payment within 30 days\"");

      assertEquals("AAI", note.getSubjectCode());
      assertEquals("payment within 30 days", note.getText());
    }

    @Test
    @DisplayName("a note with no subject code is all text")
    void plainNoteIsAllText() throws Exception {
      NoteEntry note = parse("\"just a remark\"");

      assertNull(note.getSubjectCode());
      assertEquals("just a remark", note.getText());
    }

    @Test
    @DisplayName("something that only looks like a code is left as text")
    void nearMissIsNotACode() throws Exception {
      // The pattern wants 2-5 uppercase letters between hashes. A single letter, lowercase, or
      // a digit is a sender writing prose, and promoting it to a subject code would invent a
      // routing instruction they never gave.
      assertNull(parse("\"#A#too short\"").getSubjectCode());
      assertNull(parse("\"#aai#lowercase\"").getSubjectCode());
      assertNull(parse("\"#AAI1#has a digit\"").getSubjectCode());
      assertEquals("#A#too short", parse("\"#A#too short\"").getText());
    }

    @Test
    @DisplayName("a multi-line note keeps its line breaks")
    void multiLineNote() throws Exception {
      // DOTALL on the pattern: a note that wrapped would otherwise lose everything after the
      // first newline.
      NoteEntry note = parse("\"#AAI#line one\\nline two\"");

      assertEquals("AAI", note.getSubjectCode());
      assertTrue(note.getText().contains("line two"));
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed from the text")
    void textIsTrimmed() throws Exception {
      assertEquals("remark", parse("\"#AAI#   remark   \"").getText());
      assertEquals("remark", parse("\"   remark   \"").getText());
    }

    @Test
    @DisplayName("a note sent as an object is read field by field")
    void objectFormIsAccepted() throws Exception {
      NoteEntry note = parse("{\"subjectCode\":\"AAI\",\"text\":\"a remark\"}");

      assertEquals("AAI", note.getSubjectCode());
      assertEquals("a remark", note.getText());
    }

    @Test
    @DisplayName("unknown fields inside an object note are skipped, nested ones included")
    void objectFormSkipsUnknownFields() throws Exception {
      NoteEntry note = parse(
          "{\"subjectCode\":\"AAI\",\"extra\":{\"deep\":[1,2]},\"text\":\"a remark\"}");

      assertEquals("AAI", note.getSubjectCode());
      assertEquals("a remark", note.getText(),
          "a nested unknown value must be skipped whole, or the fields after it are misread");
    }

    @Test
    @DisplayName("a null note inside a list stays null in both directions")
    void nullsSurviveInsideAList() throws Exception {
      // Nested rather than top-level: Jackson answers a top-level null itself and never calls
      // the custom serde, so a test at that level would pass without exercising either.
      List<NoteEntry> withNull = java.util.Arrays.asList(
          EInvoiceJsonCodec.build("AAI", "first"), null);

      String json = EInvoiceJsonCodec.mapper().writeValueAsString(withNull);
      assertEquals("[\"#AAI#first\",null]", json);

      List<NoteEntry> back = EInvoiceJsonCodec.mapper()
          .readValue(json, new TypeReference<List<NoteEntry>>() {});
      assertEquals("AAI", back.get(0).getSubjectCode());
      assertNull(back.get(1), "a null note is an absent note, not an empty one");
      // Jackson resolves both directions of a null itself and never reaches the custom serde,
      // which is why neither carries a null branch of its own.
    }

    @Test
    @DisplayName("a note with a code is written back in UBL form")
    void writesUblForm() throws Exception {
      assertEquals("\"#AAI#a remark\"",
          write(EInvoiceJsonCodec.build("AAI", "a remark")));
    }

    @Test
    @DisplayName("a note without a code is written as bare text")
    void writesBareText() throws Exception {
      assertEquals("\"a remark\"", write(EInvoiceJsonCodec.build(null, "a remark")));
      assertEquals("\"a remark\"", write(EInvoiceJsonCodec.build("", "a remark")));
      assertEquals("\"a remark\"", write(EInvoiceJsonCodec.build("   ", "a remark")),
          "a blank code is not a code, and emitting #   # would produce a note nothing parses");
    }

    @Test
    @DisplayName("absent text writes as empty rather than as the string null")
    void absentTextWritesEmpty() throws Exception {
      assertEquals("\"#AAI#\"", write(EInvoiceJsonCodec.build("AAI", null)));
      assertEquals("\"\"", write(EInvoiceJsonCodec.build(null, null)));
    }

    @Test
    @DisplayName("a note survives a full round trip through both forms")
    void roundTrip() throws Exception {
      NoteEntry original = EInvoiceJsonCodec.build("AAI", "payment within 30 days");
      NoteEntry back = parse(write(original));

      assertEquals(original.getSubjectCode(), back.getSubjectCode());
      assertEquals(original.getText(), back.getText());
    }

    @Test
    @DisplayName("a list of notes round-trips as UBL strings")
    void listRoundTrip() throws Exception {
      List<NoteEntry> notes = List.of(
          EInvoiceJsonCodec.build("AAI", "first"),
          EInvoiceJsonCodec.build(null, "second"));

      String json = EInvoiceJsonCodec.mapper().writeValueAsString(notes);
      assertEquals("[\"#AAI#first\",\"second\"]", json);

      List<NoteEntry> back = EInvoiceJsonCodec.mapper()
          .readValue(json, new TypeReference<List<NoteEntry>>() {});
      assertEquals("AAI", back.get(0).getSubjectCode());
      assertNull(back.get(1).getSubjectCode());
    }

    @Test
    @DisplayName("fromUbl tolerates a null input")
    void fromUblNull() {
      assertNull(EInvoiceJsonCodec.fromUbl(null));
    }
  }

  @Test
  @DisplayName("big decimals are written plainly, never in scientific notation")
  void bigDecimalsArePlain() throws Exception {
    // 1E+3 on an invoice total is technically the same number and unreadable to everyone who
    // has to reconcile it.
    String json = EInvoiceJsonCodec.mapper().writeValueAsString(new BigDecimal("1000.00"));
    assertEquals("1000.00", json);
  }
}
