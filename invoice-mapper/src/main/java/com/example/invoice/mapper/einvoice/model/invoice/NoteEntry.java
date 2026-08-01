package com.example.invoice.mapper.einvoice.model.invoice;

import lombok.*;

/**
 * Structured invoice note (BG-1).
 * UBL format: single <cbc:Note> with "#CODE#TEXT" prefix pattern.
 * BT-21 = subjectCode (REG, PMD, AAB, SUR, etc.)
 * BT-22 = text content after the prefix.
 *
 * JSON serialization: renders as plain string "#CODE#TEXT" via NoteEntrySerializer
 * to match v8_1 JSON Note[] string array format.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NoteEntry {
    private String subjectCode;  // BT-21
    private String text;         // BT-22

    /** Parse from UBL "#CODE#TEXT" format */
    public static NoteEntry fromUbl(String ublNote) {
        if (ublNote == null) return null;
        var m = java.util.regex.Pattern.compile("^#([A-Z]{2,5})#(.*)$", java.util.regex.Pattern.DOTALL).matcher(ublNote);
        if (m.matches()) {
            return new NoteEntry(m.group(1), m.group(2).trim());
        }
        // No prefix — text-only note (no subject code)
        return new NoteEntry(null, ublNote.trim());
    }

    /** Serialize back to UBL "#CODE#TEXT" format */
    public String toUbl() {
        if (subjectCode != null && !subjectCode.isBlank()) {
            return "#" + subjectCode + "#" + (text != null ? text : "");
        }
        return text != null ? text : "";
    }

    /** For JSON v8_1 compatibility: serialize as plain string */
    public String toJsonString() {
        return toUbl();
    }
}
