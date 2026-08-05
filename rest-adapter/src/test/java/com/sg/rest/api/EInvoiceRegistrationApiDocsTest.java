package com.sg.rest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domaininterface.model.einvoice.error.RegistrationOutcome;
import com.sg.domaininterface.model.invoice.Invoice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the published contract still says what the code does.
 *
 * <p>Documentation rots differently from code: it keeps compiling. These assertions pin the parts
 * a reader would act on and that a behaviour change would invalidate silently — the status codes,
 * the operation ids clients generate stubs from, and the response schema. Reformatting a
 * description does not fail anything; removing the 500, renaming an operation, or quietly making
 * a rejection a 4xx does.
 */
class EInvoiceRegistrationApiDocsTest {

  private static Method operation(String name) {
    return Arrays.stream(EInvoiceRegistrationApi.class.getDeclaredMethods())
        .filter(m -> m.getName().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no such operation: " + name));
  }

  private static Set<String> documentedStatuses(Method m) {
    return Arrays.stream(m.getAnnotation(ApiResponses.class).value())
        .map(ApiResponse::responseCode)
        .collect(Collectors.toUnmodifiableSet());
  }

  @Test
  @DisplayName("the API carries a tag, so the spec is not one unnamed bucket")
  void taggedAndDescribed() {
    Tag tag = EInvoiceRegistrationApi.class.getAnnotation(Tag.class);
    assertNotNull(tag, "an untagged API renders as a heap of operations with no grouping");
    assertEquals("E-invoice registration", tag.name());
    assertFalse(tag.description().isBlank());
  }

  @Test
  @DisplayName("both operations are documented and have stable ids")
  void operationsHaveStableIds() {
    // Generated client method names come from these. Renaming one is a breaking change for
    // every consumer that generates a stub, which is not obvious from the Java signature.
    assertEquals("registerEInvoice", operation("register").getAnnotation(Operation.class)
        .operationId());
    assertEquals("registerEInvoiceWithAttachments",
        operation("registerWithAttachments").getAnnotation(Operation.class).operationId());

    for (String name : List.of("register", "registerWithAttachments")) {
      Operation op = operation(name).getAnnotation(Operation.class);
      assertFalse(op.summary().isBlank(), name + " needs a summary");
      assertFalse(op.description().isBlank(), name + " needs a description");
    }
  }

  @Test
  @DisplayName("a business rejection is documented as 200, not as a client error")
  void rejectionsAreDocumentedAsSuccess() {
    // The single most misread thing about this API. If 4xx ever appears here for a refusal, a
    // client will start retrying invoices that are already stored.
    for (String name : List.of("register", "registerWithAttachments")) {
      Set<String> codes = documentedStatuses(operation(name));
      assertTrue(codes.contains("200"), name + " must document the 200 verdict");
      assertTrue(codes.contains("400"), name + " must document an unreadable request");
      assertTrue(codes.contains("500"),
          name + " must document the one response worth retrying");
      assertFalse(codes.contains("409"),
          "a duplicate is a 200 with CANCELLED, not a conflict");
      assertFalse(codes.contains("422"),
          "a refused invoice is understood and stored, not unprocessable");
    }
  }

  @Test
  @DisplayName("the 200 response is typed to the outcome, not left as a bare object")
  void successResponseIsTyped() {
    ApiResponse ok = Arrays.stream(operation("register").getAnnotation(ApiResponses.class).value())
        .filter(r -> r.responseCode().equals("200"))
        .findFirst().orElseThrow();

    assertEquals(RegistrationOutcome.class, ok.content()[0].schema().implementation(),
        "an untyped 200 leaves every consumer guessing at the verdict's shape");
    assertFalse(ok.description().isBlank());
    assertTrue(ok.description().contains("REGISTERED")
            && ok.description().contains("CANCELLED")
            && ok.description().contains("INCOMPLETE"),
        "every status the endpoint can answer with should be named where it is read");
  }

  @Test
  @DisplayName("the examples parse as JSON and show all three verdicts")
  void examplesAreUsable() throws Exception {
    ApiResponse ok = Arrays.stream(operation("register").getAnnotation(ApiResponses.class).value())
        .filter(r -> r.responseCode().equals("200"))
        .findFirst().orElseThrow();

    ExampleObject[] examples = ok.content()[0].examples();
    assertEquals(3, examples.length, "one worked example per outcome status");

    // An example that does not parse is worse than none: a reader copies it, it fails, and they
    // conclude the endpoint is broken.
    com.fasterxml.jackson.databind.ObjectMapper json =
        new com.fasterxml.jackson.databind.ObjectMapper();
    Set<String> statuses = new java.util.HashSet<>();
    for (ExampleObject example : examples) {
      var node = json.readTree(example.value());
      assertTrue(node.has("status"), example.name() + " must show the status field");
      statuses.add(node.get("status").asText());
    }
    assertEquals(Set.of("REGISTERED", "CANCELLED", "INCOMPLETE"), statuses);
  }

  @Test
  @DisplayName("the request body is typed to the invoice and explains the routing marker")
  void requestBodyIsDocumented() {
    io.swagger.v3.oas.annotations.parameters.RequestBody body =
        operation("register").getParameters()[0]
            .getAnnotation(io.swagger.v3.oas.annotations.parameters.RequestBody.class);

    assertNotNull(body, "the body is the whole request; leaving it undescribed documents nothing");
    assertTrue(body.required());
    assertEquals(Invoice.class, body.content()[0].schema().implementation());
    // The marker is the one field a sender gets wrong and cannot debug from a rejection alone.
    assertTrue(body.description().contains("endpointId"),
        "the routing marker's location must be stated where a sender will look");
    assertTrue(body.description().contains("providerReference"),
        "senders need to know their id becomes the duplicate key, not the invoice reference");
  }

  @Test
  @DisplayName("both multipart parts are described, including that uploads win")
  void multipartPartsAreDocumented() {
    Method m = operation("registerWithAttachments");
    Parameter invoicePart = m.getParameters()[0].getAnnotation(Parameter.class);
    Parameter filesPart = m.getParameters()[1].getAnnotation(Parameter.class);

    assertEquals("invoice", invoicePart.name());
    assertTrue(invoicePart.required());
    assertEquals(Invoice.class, invoicePart.content()[0].schema().implementation());

    assertEquals("files", filesPart.name());
    assertFalse(filesPart.required(), "attachments are optional at the transport level");

    // Uploaded-wins is behaviour a caller cannot infer and would be surprised by.
    assertTrue(m.getAnnotation(Operation.class).description().contains("Uploaded files win"),
        "the precedence rule between uploaded and embedded attachments must be stated");
  }
}
