package com.example.invoice.config;

import com.example.invoice.service.domain.model.invoice.Invoice;
import com.example.invoice.service.domain.model.invoice.NoteEntry;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON binding for the UBL {@link Invoice} model.
 *
 * <p>Lives in the app module rather than {@code invoice-mapper} on purpose: the mapper module
 * has no runtime Jackson dependency (its vendored model has all Jackson annotations stripped),
 * so JSON is explicitly a transport concern owned by whoever exposes the transport. This class
 * is the replacement for the source project's {@code PayloadMapper}.
 *
 * <p><b>Configuration mirrors the original.</b> Field-level visibility (not getters), because
 * the vendored model's field names are the canonical wire names; unknown properties ignored,
 * so a peer adding a field doesn't break us; single values coerced to arrays, because UBL
 * producers are inconsistent about repeated elements.
 *
 * <p><b>NoteEntry gets a custom serde</b> carrying forward the object-form deserialisation fix
 * from the source project (A@3430b2b). Without it, an object-shaped note
 * ({@code {"subjectCode":"BAR","text":"B2B"}}) makes Jackson feed each inner token to the
 * deserializer separately, producing several bogus {@code NoteEntry} instances per real note.
 * Both the string form ({@code "#BAR#B2B"}) and the object form are accepted.
 */
public final class EInvoiceJsonCodec {

  private static final Pattern UBL_NOTE_PATTERN =
      Pattern.compile("^#([A-Z]{2,5})#(.*)$", Pattern.DOTALL);

  private static final ObjectMapper MAPPER = build();

  private EInvoiceJsonCodec() {}

  private static ObjectMapper build() {
    ObjectMapper m = new ObjectMapper();
    m.registerModule(new JavaTimeModule());
    m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    m.enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN);
    m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    m.enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
    m.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    // Bind by field name, not getter name — the vendored model's fields are the wire contract.
    m.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
    m.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

    SimpleModule noteModule = new SimpleModule();
    noteModule.addSerializer(NoteEntry.class, new NoteSerializer());
    noteModule.addDeserializer(NoteEntry.class, new NoteDeserializer());
    m.registerModule(noteModule);
    return m;
  }

  public static ObjectMapper mapper() {
    return MAPPER;
  }

  public static Invoice fromJson(String json) {
    try {
      return MAPPER.readValue(json, Invoice.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("JSON→Invoice failed: " + e.getOriginalMessage(), e);
    }
  }

  public static String toJson(Invoice invoice) {
    try {
      return MAPPER.writeValueAsString(invoice);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Invoice→JSON failed: " + e.getOriginalMessage(), e);
    }
  }

  // ── NoteEntry serde ───────────────────────────────────────────────────────

  /** Always emits the UBL-compatible {@code "#CODE#TEXT"} string form. */
  static final class NoteSerializer extends StdSerializer<NoteEntry> {
    NoteSerializer() { super(NoteEntry.class); }

    @Override
    public void serialize(NoteEntry value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      if (value == null) { gen.writeNull(); return; }
      String subjectCode = value.getSubjectCode();
      String text = value.getText();
      if (subjectCode != null && !subjectCode.isBlank()) {
        gen.writeString("#" + subjectCode + "#" + (text != null ? text : ""));
      } else {
        gen.writeString(text != null ? text : "");
      }
    }
  }

  /** Accepts both the {@code "#CODE#TEXT"} string form and the structured object form. */
  static final class NoteDeserializer extends StdDeserializer<NoteEntry> {
    NoteDeserializer() { super(NoteEntry.class); }

    @Override
    public NoteEntry deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      JsonToken tok = p.currentToken();
      if (tok == JsonToken.VALUE_NULL) return null;

      if (tok == JsonToken.START_OBJECT) {
        String subjectCode = null;
        String text = null;
        while (p.nextToken() != JsonToken.END_OBJECT) {
          if (p.currentToken() != JsonToken.FIELD_NAME) continue;
          String field = p.currentName();
          p.nextToken();
          switch (field) {
            case "subjectCode" -> subjectCode = p.getValueAsString();
            case "text" -> text = p.getValueAsString();
            default -> p.skipChildren();
          }
        }
        return build(subjectCode, text);
      }
      return fromUbl(p.getValueAsString());
    }
  }

  static NoteEntry build(String subjectCode, String text) {
    NoteEntry n = new NoteEntry();
    n.setSubjectCode(subjectCode);
    n.setText(text);
    return n;
  }

  static NoteEntry fromUbl(String ublNote) {
    if (ublNote == null) return null;
    Matcher m = UBL_NOTE_PATTERN.matcher(ublNote);
    if (m.matches()) {
      return build(m.group(1), m.group(2).trim());
    }
    return build(null, ublNote.trim());
  }
}
