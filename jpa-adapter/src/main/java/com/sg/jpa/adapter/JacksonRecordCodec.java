package com.sg.jpa.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.port.out.RecordCodec;
import java.util.List;

/**
 * The quarantine row's payload columns, as JSON.
 *
 * <p>{@code JdbcQuarantineStore} keeps two snapshots of a referential response — what arrived
 * ({@code raw_payload}) and what an operator corrected it to ({@code corrected_payload}) — and
 * reads them back to serve the correction in place of the upstream answer. This is what turns
 * those lists into a column value and back.
 *
 * <p>It lives here rather than in the domain because the format is a property of how the row is
 * stored, not of what the row means. Nothing outside this adapter needs to agree with it.
 *
 * <p><b>Null round-trips as null.</b> An absent correction and an empty one are different states:
 * a row with no correction is still waiting for an operator, while one corrected to an empty list
 * is a deliberate "serve nothing for this party". Writing {@code "[]"} for both would collapse
 * the two and quietly resolve every pending row.
 */
public final class JacksonRecordCodec implements RecordCodec {

  private static final TypeReference<List<PartyRegistrationDetails>> RECORD_LIST =
      new TypeReference<>() {};

  private final ObjectMapper mapper;

  public JacksonRecordCodec() {
    this(JsonMapper.builder()
        .addModule(new JavaTimeModule())
        // A referential that adds a field must not make every quarantined row unreadable. These
        // are stored snapshots: they were written by an older version of the model by
        // definition, and rejecting them would break exactly the rows an operator is trying to
        // correct.
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build());
  }

  /** Visible for callers that want the application's own mapper rather than this default. */
  public JacksonRecordCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public String serialize(List<PartyRegistrationDetails> records) {
    if (records == null) {
      return null;
    }
    try {
      return mapper.writeValueAsString(records);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new JdbcQuarantineStore.QuarantineStoreException(
          "could not serialise " + records.size() + " registration record(s) for the "
              + "quarantine row", e);
    }
  }

  @Override
  public List<PartyRegistrationDetails> deserialize(String payload) {
    if (payload == null || payload.isBlank()) {
      return null;
    }
    try {
      return mapper.readValue(payload, RECORD_LIST);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // Raised rather than swallowed to null: a null reads as "no correction recorded", which
      // would send an operator's correction back to the referential's original answer without
      // anything saying the stored one could not be read.
      throw new JdbcQuarantineStore.QuarantineStoreException(
          "could not read the stored quarantine payload", e);
    }
  }
}
