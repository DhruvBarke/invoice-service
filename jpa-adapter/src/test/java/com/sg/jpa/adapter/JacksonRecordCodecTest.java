package com.sg.jpa.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The quarantine row's payload columns, round-tripped.
 *
 * <p>Both columns hold an operator-facing snapshot: what the referential returned, and what
 * somebody corrected it to. Getting either wrong is not a crash — it is a correction that
 * silently stops being served.
 */
class JacksonRecordCodecTest {

  private final JacksonRecordCodec codec = new JacksonRecordCodec();

  private static PartyRegistrationDetails party(String siren) {
    return new PartyRegistrationDetails(
        "ELEM-1", "Lyon", "LYON", "TP-1", "Acme SA", "ACME",
        "G1", "Acme SA", "ACME", siren, "12345678900012", List.of());
  }

  @Test
  @DisplayName("a list survives the round trip with its identifying fields intact")
  void roundTrip() {
    List<PartyRegistrationDetails> original = List.of(party("123456789"), party("987654321"));

    List<PartyRegistrationDetails> back = codec.deserialize(codec.serialize(original));

    assertEquals(2, back.size());
    assertEquals("123456789", back.get(0).siren());
    assertEquals("G1", back.get(0).goldenBdrId());
    assertEquals("12345678900012", back.get(0).siret());
    assertEquals("987654321", back.get(1).siren());
  }

  @Test
  @DisplayName("null round-trips as null, and is not the same as an empty list")
  void nullIsNotEmpty() {
    // A row with no correction is still waiting for an operator; one corrected to an empty list
    // is a deliberate "serve nothing for this party". Writing "[]" for both would collapse the
    // two and quietly resolve every pending row.
    assertNull(codec.serialize(null));
    assertNull(codec.deserialize(null));
    assertNull(codec.deserialize("   "), "a blank column is an absent payload, not a broken one");

    assertEquals("[]", codec.serialize(List.of()));
    assertEquals(List.of(), codec.deserialize("[]"));
  }

  @Test
  @DisplayName("an unknown field does not make a stored row unreadable")
  void unknownFieldsAreTolerated() {
    // These are snapshots written by an older version of the model by definition. Rejecting them
    // would break exactly the rows an operator is trying to correct.
    List<PartyRegistrationDetails> back = codec.deserialize(
        "[{\"siren\":\"123456789\",\"goldenBdrId\":\"G1\",\"somethingAddedLater\":\"x\"}]");

    assertEquals(1, back.size());
    assertEquals("123456789", back.get(0).siren());
  }

  @Test
  @DisplayName("an unreadable payload is raised, never quietly read as no correction")
  void unreadablePayloadIsRaised() {
    // Returning null here would send an operator's correction back to the referential's original
    // answer, with nothing saying the stored one could not be read.
    JdbcQuarantineStore.QuarantineStoreException thrown =
        assertThrows(JdbcQuarantineStore.QuarantineStoreException.class,
            () -> codec.deserialize("{not json"));

    assertTrue(thrown.getMessage().contains("quarantine payload"), thrown.getMessage());
  }

  @Test
  @DisplayName("a value the mapper cannot write is raised rather than stored half-formed")
  void unserialisableValueIsRaised() {
    com.fasterxml.jackson.databind.ObjectMapper refusing =
        new com.fasterxml.jackson.databind.ObjectMapper() {
          private static final long serialVersionUID = 1L;

          @Override
          public String writeValueAsString(Object value)
              throws com.fasterxml.jackson.core.JsonProcessingException {
            throw new com.fasterxml.jackson.core.JsonGenerationException("no", (com.fasterxml.jackson.core.JsonGenerator) null);
          }
        };

    assertThrows(JdbcQuarantineStore.QuarantineStoreException.class,
        () -> new JacksonRecordCodec(refusing).serialize(List.of(party("123456789"))));
  }
}
