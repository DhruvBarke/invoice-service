/**
 * UBL Invoice model — 63 classes forming the {@link Invoice} graph.
 *
 * <p>Vendored from A's {@code com.sg.einvoicing.domain.model.invoice} package (the in-repo
 * mirror of feesone-commons {@code Invoice}). One deliberate omission and one universal edit:
 *
 * <ul>
 *   <li><b>Omitted:</b> {@code NoteEntrySerde}. It is a Jackson {@code StdSerializer /
 *       StdDeserializer} pair that only makes sense when serialising to/from JSON. This
 *       module has no runtime Jackson dependency — mappers transform between object shapes
 *       directly. JSON binding is the caller's concern; if a consumer needs the {@code
 *       "#CODE#TEXT"} UBL wire format, they can register their own serde on top of
 *       {@link NoteEntry}.</li>
 *   <li><b>Universal edit:</b> Jackson annotations stripped ({@code @JsonIgnoreProperties},
 *       {@code @JsonCreator}) — they were compile-time markers for JSON binding only. Where
 *       {@code @JsonCreator} tagged a static {@code fromString} factory (see
 *       {@link CodedValue}, {@link SchemeID}, {@link CurrencyAmount}), the factory itself is
 *       retained as a plain helper.</li>
 * </ul>
 *
 * <p>The class shape ({@code @Data @Builder @NoArgsConstructor @AllArgsConstructor}) is
 * preserved verbatim so callers can construct instances via either the builder or setters,
 * matching how A's mappers do it. Lombok is a {@code provided}-scope compile dep — see
 * {@code invoice-mapper/pom.xml}.
 */
package com.example.invoice.mapper.einvoice.model.invoice;
