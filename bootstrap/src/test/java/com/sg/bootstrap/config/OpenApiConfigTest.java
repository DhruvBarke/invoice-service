package com.sg.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the published spec actually says something.
 *
 * <p>This class lives in the jacoco-excluded {@code config} package, so nothing else would notice
 * if the document were emptied — the build would stay green and the API would serve a spec with a
 * blank title and no server. These assertions are the only thing standing between that and a
 * consumer generating a client against it.
 */
class OpenApiConfigTest {

  private static OpenAPI document(String version) {
    return new OpenApiConfig(version, "", "").invoiceServiceOpenApi();
  }

  private static OpenAPI document(String version, String contactName, String contactEmail) {
    return new OpenApiConfig(version, contactName, contactEmail).invoiceServiceOpenApi();
  }

  @Test
  @DisplayName("the document carries a title, a version and a contact")
  void infoIsPopulated() {
    OpenAPI api = document("2.1.0");

    assertNotNull(api.getInfo());
    assertTrue(api.getInfo().getTitle().contains("Invoice Service"));
    assertEquals("2.1.0", api.getInfo().getVersion());
    assertNull(api.getInfo().getLicense(),
        "there is no licence on this service, and declaring one would be a claim nobody made");
  }

  @Test
  @DisplayName("no contact is published unless one is configured")
  void contactIsOmittedWhenUnset() {
    // An invented address sends a reader with a real question to a mailbox that does not exist,
    // which is worse for them than a field they can see is empty.
    assertNull(document("1.0.0").getInfo().getContact());
    assertNull(document("1.0.0", "   ", "  ").getInfo().getContact(),
        "blank is the same as unset — a whitespace value in a config file is not an answer");
  }

  @Test
  @DisplayName("a configured contact is published, and a half-configured one is not padded")
  void contactIsPublishedWhenSet() {
    OpenAPI both = document("1.0.0", "Invoice Platform", "invoices@sgcib.com");
    assertNotNull(both.getInfo().getContact());
    assertEquals("Invoice Platform", both.getInfo().getContact().getName());
    assertEquals("invoices@sgcib.com", both.getInfo().getContact().getEmail());

    // Only one half set still produces a contact — with the other half absent rather than
    // filled with something plausible.
    OpenAPI emailOnly = document("1.0.0", "", "invoices@sgcib.com");
    assertNotNull(emailOnly.getInfo().getContact());
    assertNull(emailOnly.getInfo().getContact().getName());
    assertEquals("invoices@sgcib.com", emailOnly.getInfo().getContact().getEmail());

    OpenAPI nameOnly = document("1.0.0", "Invoice Platform", "");
    assertNotNull(nameOnly.getInfo().getContact());
    assertEquals("Invoice Platform", nameOnly.getInfo().getContact().getName());
    assertNull(nameOnly.getInfo().getContact().getEmail());
  }

  @Test
  @DisplayName("the version comes from configuration, not from the artifact")
  void versionIsInjected() {
    // Hard-coding it would have every environment serve a spec claiming to be the build it came
    // from, which is exactly the field a reader checks to see what they are talking to.
    assertEquals("0.0.1-SNAPSHOT", document("0.0.1-SNAPSHOT").getInfo().getVersion());
    assertEquals("2.1.0", document("2.1.0").getInfo().getVersion());
  }

  @Test
  @DisplayName("no server is declared, so springdoc infers it from the request")
  void noHardCodedServer() {
    // A configured server URL is only right in the environments that remember to override it.
    // Leaving it out lets springdoc follow whatever proxy or ingress the reader came through,
    // rather than publishing localhost in a document people generate clients from.
    OpenAPI api = document("1.0.0");
    assertTrue(api.getServers() == null || api.getServers().isEmpty(),
        "a declared server that nobody overrode is worse than none at all");
  }

  @Test
  @DisplayName("the description explains how to read a response")
  void descriptionCoversTheStatusContract() {
    // The single most misread thing about this API: business failures are 200s. A client that
    // retries a refusal resubmits an invoice that is already recorded, so the spec has to say so
    // rather than leaving it to the per-operation text.
    String description = document("1.0.0").getInfo().getDescription();

    assertFalse(description.isBlank());
    assertTrue(description.contains("200"), description);
    assertTrue(description.contains("retry") || description.contains("retrying"), description);
    assertTrue(description.contains("invoice_flow"),
        "the e-invoicing-only scope is what stops a reader assuming manual capture comes here");
  }
}
