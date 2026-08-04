package com.example.invoice.pipeline.testsupport;

import com.example.invoice.service.domain.model.invoice.ExtractedAttachment;
import com.example.invoice.service.domain.model.invoice.Invoice;
import com.example.invoice.service.domain.model.invoice.NoteEntry;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads the e-invoice JSON samples under {@code src/test/resources/einvoice-samples}.
 *
 * <p>Tests bind from real JSON rather than building object graphs in code on purpose: a
 * fixture shaped like what a PDP actually posts catches binding mistakes (a field nested one
 * level too deep, a code wrapped in the wrong holder type) that a builder chain accepts
 * silently. The samples double as documentation of the wire format.
 *
 * <p>The mapper configuration mirrors the app module's {@code EInvoiceJsonCodec} — field-level
 * visibility, unknown properties ignored, single values coerced to arrays, and the NoteEntry
 * dual-form deserialiser. Duplicated deliberately: the registration module cannot depend on the
 * app module (that edge would be backwards), and a test helper drifting from production
 * binding is exactly the kind of thing these tests should catch rather than hide.
 */
public final class Fixtures {

  private static final ObjectMapper MAPPER = build();

  private Fixtures() {}

  private static ObjectMapper build() {
    ObjectMapper m = new ObjectMapper();
    m.registerModule(new JavaTimeModule());
    m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    m.enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
    m.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
    m.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

    SimpleModule notes = new SimpleModule();
    notes.addDeserializer(NoteEntry.class, new NoteDeserializer());
    m.registerModule(notes);
    return m;
  }

  /** @param name file name under {@code einvoice-samples/}, e.g. {@code custody-with-lines.json} */
  public static Invoice loadInvoice(String name) {
    String path = "einvoice-samples/" + name;
    try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalArgumentException("fixture not found on the test classpath: " + path);
      }
      return MAPPER.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), Invoice.class);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read fixture " + path, e);
    }
  }

  /** A well-formed PDF attachment — magic bytes {@code %PDF} so the extractor accepts it. */
  public static ExtractedAttachment pdf(String filename) {
    byte[] bytes = "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
    return new ExtractedAttachment(filename, bytes, "application/pdf");
  }

  /** A CSV trade file — satisfies {@code BrokerageTradeFileRule}. */
  public static ExtractedAttachment tradeCsv(String filename) {
    byte[] bytes = "tradeId,qty,price\nT1,100,12.5\nT2,250,11.0\n".getBytes(StandardCharsets.UTF_8);
    return new ExtractedAttachment(filename, bytes, "text/csv");
  }

  /** An XLSX trade file — OOXML magic bytes {@code PK\003\004}. */
  public static ExtractedAttachment tradeXlsx(String filename) {
    byte[] bytes = new byte[] {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x06, 0x00};
    return new ExtractedAttachment(filename, bytes, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
  }

  /** Accepts both {@code "#CODE#TEXT"} and the structured object form. */
  private static final class NoteDeserializer extends StdDeserializer<NoteEntry> {
    private static final long serialVersionUID = 1L;

    NoteDeserializer() { super(NoteEntry.class); }

    @Override
    public NoteEntry deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      if (p.currentToken() == JsonToken.VALUE_NULL) return null;
      NoteEntry n = new NoteEntry();
      if (p.currentToken() == JsonToken.START_OBJECT) {
        while (p.nextToken() != JsonToken.END_OBJECT) {
          if (p.currentToken() != JsonToken.FIELD_NAME) continue;
          String field = p.currentName();
          p.nextToken();
          switch (field) {
            case "subjectCode" -> n.setSubjectCode(p.getValueAsString());
            case "text" -> n.setText(p.getValueAsString());
            default -> p.skipChildren();
          }
        }
        return n;
      }
      n.setText(p.getValueAsString());
      return n;
    }
  }
}
