package com.sg.rest.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domaininterface.model.einvoice.error.RegistrationOutcome;
import com.sg.domaininterface.model.invoice.Invoice;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;

/**
 * That the e-invoice's own JSON contract is the one used to bind the request body.
 *
 * <p>This is the test that would have caught the endpoint changing from "read the file and call
 * the codec" to "let Spring bind it": the codec kept passing its own tests the whole time, while
 * nothing on the request path used it any more.
 */
class EInvoiceHttpMessageConverterTest {

  private final EInvoiceHttpMessageConverter converter = new EInvoiceHttpMessageConverter();

  private static HttpInputMessage body(String json) {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    return new HttpInputMessage() {
      @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
      @Override public HttpHeaders getHeaders() { return new HttpHeaders(); }
    };
  }

  @Test
  @DisplayName("it uses the codec's mapper, not a fresh one")
  void usesTheCodecMapper() {
    // Not cosmetic: a mapper built here would drift from the one the codec's tests cover, and
    // the two would disagree without either failing.
    assertSame(EInvoiceJsonCodec.mapper(), converter.getObjectMapper());
  }

  @Test
  @DisplayName("it claims Invoice and declines everything else")
  void scopedToInvoice() {
    assertTrue(converter.canRead(Invoice.class, Invoice.class, MediaType.APPLICATION_JSON));
    assertTrue(converter.canWrite(Invoice.class, MediaType.APPLICATION_JSON));

    // Leaving the defaults to handle the response matters: field-level visibility and non-null
    // inclusion are right for the vendored UBL model and would quietly reshape an outcome.
    assertFalse(converter.canRead(
        RegistrationOutcome.class, RegistrationOutcome.class, MediaType.APPLICATION_JSON));
    assertFalse(converter.canWrite(RegistrationOutcome.class, MediaType.APPLICATION_JSON));
    assertFalse(converter.canWrite(String.class, MediaType.APPLICATION_JSON));
  }

  @Test
  @DisplayName("the right type on the wrong media type is still declined")
  void mediaTypeStillApplies() {
    // Claiming Invoice regardless of media type would have this converter answer for an XML or
    // form-encoded body it cannot read, and the request would fail inside Jackson rather than
    // as an unsupported media type.
    assertFalse(converter.canRead(Invoice.class, Invoice.class, MediaType.APPLICATION_XML));
    assertFalse(converter.canWrite(Invoice.class, MediaType.TEXT_PLAIN));
  }

  @Test
  @DisplayName("a parameterised type containing Invoice is not this contract")
  void parameterisedTypesAreDeclined() {
    // A List<Invoice> is somebody else's payload shape. Claiming it would apply the vendored
    // model's binding rules to a structure that never agreed to them.
    java.lang.reflect.Type listOfInvoice =
        new org.springframework.core.ParameterizedTypeReference<List<Invoice>>() {}.getType();
    assertFalse(converter.canRead(listOfInvoice, null, MediaType.APPLICATION_JSON));
  }

  @Test
  @DisplayName("the UBL note format survives the round trip through the converter")
  void noteFormatIsHonoured() throws IOException {
    // The reason this converter exists. Spring's default mapper has no NoteEntry deserializer
    // and would fail the request outright, or bind it as a bare string and lose the subject code.
    Invoice invoice = (Invoice) converter.read(Invoice.class, null,
        body("{\"id\":\"SUP-INV-1\",\"note\":\"#AAI#payment within 30 days\"}"));

    assertNotNull(invoice);
    assertEquals("SUP-INV-1", invoice.getId());
    assertNotNull(invoice.getNote(), "the note bound rather than being dropped");
  }

  @Test
  @DisplayName("unknown properties do not fail the request")
  void unknownPropertiesAreTolerated() throws IOException {
    // Senders add fields. Rejecting the whole invoice for one we do not read would make every
    // upstream schema addition an outage.
    Invoice invoice = (Invoice) converter.read(Invoice.class, null,
        body("{\"id\":\"SUP-INV-1\",\"somethingNewUpstream\":\"value\"}"));

    assertEquals("SUP-INV-1", invoice.getId());
  }

  @Test
  @DisplayName("a single value binds into a collection field")
  void singleValueBindsAsArray() throws IOException {
    // UBL routinely sends a one-element collection as a bare object rather than an array.
    Invoice invoice = (Invoice) converter.read(Invoice.class, null,
        body("{\"id\":\"X\",\"invoiceLine\":{\"id\":\"1\"}}"));

    assertNotNull(invoice.getInvoiceLine());
    assertEquals(1, invoice.getInvoiceLine().size());
  }
}
