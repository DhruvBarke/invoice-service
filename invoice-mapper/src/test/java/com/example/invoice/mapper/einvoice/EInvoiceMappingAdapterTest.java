package com.example.invoice.mapper.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.invoice.service.domain.einvoice.Business;
import com.example.invoice.service.domain.einvoice.error.ErrorCode;
import com.example.invoice.service.domain.einvoice.error.MappingError;
import com.example.invoice.service.domain.einvoice.port.EInvoiceMappingPort.MappingResult;
import com.example.invoice.service.domain.model.PartyRegistrationDetails;
import com.example.invoice.service.domain.model.invoice.AdditionalDocumentReference;
import com.example.invoice.service.domain.model.invoice.Attachment;
import com.example.invoice.service.domain.model.invoice.AccountingCustomerParty;
import com.example.invoice.service.domain.model.invoice.AccountingSupplierParty;
import com.example.invoice.service.domain.model.invoice.EmbeddedDocument;
import com.example.invoice.service.domain.model.invoice.SchemeID;
import com.example.invoice.service.domain.model.invoice.Invoice;
import com.example.invoice.service.domain.model.invoice.Party;
import com.example.invoice.service.domain.model.invoice.PartyLegalEntity;
import com.example.invoice.service.domain.port.in.PartyRegistrationLookup;
import com.example.invoice.service.domain.port.in.PartyRegistrationUnavailableException;
import com.example.invoice.service.domain.port.in.UnavailabilityReason;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The mapping stack behind the port: marker, fee identity, payable, attachments — and the
 * defects found along the way.
 *
 * <p>The behaviour worth pinning here is that a bad document does not stop the read. Every test
 * that supplies one defect checks that the rest of the mapping still happened, because the
 * alternative — abort on the first problem — makes a sender fix one thing, resubmit, and find
 * the next.
 */
class EInvoiceMappingAdapterTest {

  private static final PartyRegistrationDetails ACME = new PartyRegistrationDetails(
      "ELEM-9", "Lyon branch", "LYON", "TP-1", "Acme SA", "ACME",
      "BDR-G-001", "Acme SA", "ACME", "123456789", "12345678900012", List.of());

  private static PartyRegistrationLookup lookup() {
    return new PartyRegistrationLookup() {
      @Override public Optional<PartyRegistrationDetails> findByBdrId(String b) {
        return Optional.of(ACME);
      }
      @Override public Optional<PartyRegistrationDetails> findBySiren(String s) {
        return Optional.of(ACME);
      }
      @Override public Optional<PartyRegistrationDetails> findBySiret(String s) {
        return Optional.of(ACME);
      }
      @Override public List<PartyRegistrationDetails> findAllBySiret(String s) {
        return List.of(ACME);
      }
    };
  }

  private static FeeTypeMatcher matcher() {
    Map<String, String> ref = Map.of("F01", "CUSTODY", "F04", "BROKERAGE_PRINCIPAL");
    return new FeeTypeMatcher(() -> ref);
  }

  private static EInvoiceMappingAdapter adapter() {
    return adapter(new EInvoiceFacadeMapper(lookup()), matcher(),
        new MultipartExtractionService());
  }

  private static EInvoiceMappingAdapter adapter(EInvoiceFacadeMapper m, FeeTypeMatcher f,
                                                MultipartExtractionService e) {
    return new EInvoiceMappingAdapter(m, f, e);
  }

  /** An invoice whose receiver endpoint carries {@code marker}. */
  private static Invoice invoiceWithMarker(String marker) {
    Invoice inv = new Invoice();
    inv.setId("SUP-INV-1");
    if (marker != null) {
      Party party = new Party();
      SchemeID endpoint = new SchemeID();
      endpoint.setValue(marker);
      party.setEndpointId(endpoint);
      AccountingCustomerParty customer = new AccountingCustomerParty();
      customer.setParty(party);
      inv.setAccountingCustomerParty(customer);
    }
    return inv;
  }

  private static boolean has(MappingResult r, ErrorCode code) {
    return r.errors().stream().anyMatch(e -> e.code() == code);
  }

  // ── The fee matcher, which is the reason this class exists ────────────────

  @Nested
  @DisplayName("fee identity")
  class Fee {

    @Test
    @DisplayName("a resolved fee lands on the payable, not just on the result")
    void resolvedFeeReachesThePayable() {
      MappingResult r = adapter().map(invoiceWithMarker("552120222_MARK_CUSTODY"));

      assertEquals("F01", r.feeId());
      assertEquals("CUSTODY", r.feeType());
      // The point of moving the matcher into mapping: the model is complete when mapping ends,
      // with nothing left for a later step to patch onto it.
      assertEquals("CUSTODY", r.model().getFeeCategory());
      assertEquals("F01", r.model().getInvoicePayable().getFeeCategoryCode());
      assertTrue(r.errors().isEmpty());
    }

    @Test
    @DisplayName("an unresolvable token is reported but still recorded verbatim")
    void unresolvedFeeKeepsTheRawToken() {
      MappingResult r = adapter().map(invoiceWithMarker("552120222_MARK_NOT_A_FEE"));

      assertTrue(has(r, ErrorCode.FEETYPE_UNRESOLVED));
      assertNull(r.feeId(), "nothing matched, so there is no referential id");
      assertEquals("NOT_A_FEE", r.feeType(),
          "the sender's own token is kept — a blank column would lose what they actually said");
      assertNotNull(r.model(), "one bad token does not stop the rest of the document being read");
    }

    @Test
    @DisplayName("a matcher that throws is reported, not propagated")
    void throwingMatcherIsCaptured() {
      FeeTypeMatcher exploding = new FeeTypeMatcher(() -> {
        throw new IllegalStateException("referential is down");
      });
      MappingResult r = adapter(new EInvoiceFacadeMapper(lookup()), exploding,
          new MultipartExtractionService())
          .map(invoiceWithMarker("552120222_MARK_CUSTODY"));

      assertTrue(r.errors().stream().anyMatch(e ->
          e.code() == ErrorCode.FEETYPE_UNRESOLVED
              && e.detail().contains("fee-type provider failure")));
      assertNotNull(r.model());
    }

    @Test
    @DisplayName("a referential that fills in mid-resolve still yields a usable message")
    void referentialRaceStillExplains() {
      // resolveOrNull and explainFailure read the provider separately. If the entry appears
      // between the two calls, the first says "no match" and the second — now able to resolve
      // it — reports no reason at all. The error still has to say something: an alert reading
      // "unresolved fee type 'CUSTODY': null" tells an operator nothing about a fee that, by
      // then, resolves perfectly well.
      Map<String, String> empty = Map.of();
      Map<String, String> populated = Map.of("F01", "CUSTODY");
      java.util.concurrent.atomic.AtomicInteger call =
          new java.util.concurrent.atomic.AtomicInteger();
      FeeTypeMatcher racy = new FeeTypeMatcher(
          () -> call.getAndIncrement() == 0 ? empty : populated);

      MappingResult r = adapter(new EInvoiceFacadeMapper(lookup()), racy,
          new MultipartExtractionService()).map(invoiceWithMarker("552120222_MARK_CUSTODY"));

      MappingError feeError = r.errors().stream()
          .filter(e -> e.code() == ErrorCode.FEETYPE_UNRESOLVED)
          .findFirst().orElseThrow();
      assertTrue(feeError.detail().contains("no reason available"),
          "the placeholder stands in for the explanation the referential declined to give");
      assertFalse(feeError.detail().contains("null"));
    }

    @Test
    @DisplayName("a malformed marker is one problem, not two")
    void missingFeeTokenIsNotAlsoUnresolved() {
      MappingResult r = adapter().map(invoiceWithMarker("552120222_MARK"));

      assertTrue(has(r, ErrorCode.MARKER_MALFORMED));
      assertFalse(has(r, ErrorCode.FEETYPE_UNRESOLVED),
          "there was no token to resolve, so reporting it as unresolvable would make one "
              + "defect look like two in the alert");
    }
  }

  // ── Marker ────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("marker")
  class Marker {

    @Test
    @DisplayName("an absent endpoint is malformed, and mapping continues regardless")
    void absentEndpoint() {
      MappingResult r = adapter().map(invoiceWithMarker(null));

      assertTrue(has(r, ErrorCode.MARKER_MALFORMED));
      assertFalse(has(r, ErrorCode.BUSINESS_UNKNOWN),
          "an absent marker is reported once, as absent");
      assertNotNull(r.marker(), "never null — callers read business() without checking");
      assertNull(r.marker().business());
    }

    @Test
    @DisplayName("a blank endpoint reads the same as an absent one")
    void blankEndpoint() {
      assertTrue(has(adapter().map(invoiceWithMarker("   ")), ErrorCode.MARKER_MALFORMED));
    }

    @Test
    @DisplayName("an unknown business token is reported without guessing a business")
    void unknownBusiness() {
      MappingResult r = adapter().map(invoiceWithMarker("552120222_NOPE_CUSTODY"));

      assertTrue(has(r, ErrorCode.BUSINESS_UNKNOWN));
      assertNull(r.marker().business(), "guessing would run another business's rule set");
      assertEquals("CUSTODY", r.feeType(), "the fee still resolved on its own");
    }

    @Test
    @DisplayName("a customer party with no party, or no endpoint, is not a crash")
    void partialCustomerParty() {
      Invoice noParty = new Invoice();
      noParty.setId("X");
      noParty.setAccountingCustomerParty(new AccountingCustomerParty());
      assertTrue(has(adapter().map(noParty), ErrorCode.MARKER_MALFORMED));

      Invoice noEndpoint = new Invoice();
      noEndpoint.setId("X");
      AccountingCustomerParty cp = new AccountingCustomerParty();
      cp.setParty(new Party());
      noEndpoint.setAccountingCustomerParty(cp);
      assertTrue(has(adapter().map(noEndpoint), ErrorCode.MARKER_MALFORMED));
    }
  }

  // ── Mapping failures ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("mapping failures")
  class Failures {

    @Test
    @DisplayName("a referential outage is its own error code, not a generic mapping fault")
    void partyLookupFailure() {
      PartyRegistrationLookup down = new PartyRegistrationLookup() {
        @Override public Optional<PartyRegistrationDetails> findByBdrId(String b) {
          throw new PartyRegistrationUnavailableException(
              UnavailabilityReason.UPSTREAM_UNAVAILABLE, "SIREN", "123456789", "referential down");
        }
        @Override public Optional<PartyRegistrationDetails> findBySiren(String s) {
          throw new PartyRegistrationUnavailableException(
              UnavailabilityReason.UPSTREAM_UNAVAILABLE, "SIREN", "123456789", "referential down");
        }
        @Override public Optional<PartyRegistrationDetails> findBySiret(String s) {
          throw new PartyRegistrationUnavailableException(
              UnavailabilityReason.UPSTREAM_UNAVAILABLE, "SIREN", "123456789", "referential down");
        }
        @Override public List<PartyRegistrationDetails> findAllBySiret(String s) {
          throw new PartyRegistrationUnavailableException(
              UnavailabilityReason.UPSTREAM_UNAVAILABLE, "SIREN", "123456789", "referential down");
        }
      };

      Invoice inv = invoiceWithMarker("552120222_MARK_CUSTODY");
      inv.setAccountingSupplierParty(supplierWith("123456789"));

      MappingResult r = adapter(new EInvoiceFacadeMapper(down), matcher(),
          new MultipartExtractionService()).map(inv);

      // Distinguished on purpose: this is not the sender's fault and must not be answered with
      // a refusal telling them their invoice was wrong.
      assertTrue(has(r, ErrorCode.PARTY_LOOKUP_FAILED));
      assertNull(r.model());
    }

    @Test
    @DisplayName("any other mapper failure becomes MAPPING_ERROR with the model left null")
    void genericMapperFailure() {
      // EInvoiceFacadeMapper is final, so the failure is injected through its one collaborator.
      // A plain RuntimeException from the lookup is not the party-unavailable type, so it takes
      // the generic arm — which is the distinction being tested.
      PartyRegistrationLookup broken = new PartyRegistrationLookup() {
        @Override public Optional<PartyRegistrationDetails> findByBdrId(String b) {
          throw new IllegalStateException("mapper is broken");
        }
        @Override public Optional<PartyRegistrationDetails> findBySiren(String s) {
          throw new IllegalStateException("mapper is broken");
        }
        @Override public Optional<PartyRegistrationDetails> findBySiret(String s) {
          throw new IllegalStateException("mapper is broken");
        }
        @Override public List<PartyRegistrationDetails> findAllBySiret(String s) {
          throw new IllegalStateException("mapper is broken");
        }
      };
      Invoice inv = invoiceWithMarker("552120222_MARK_CUSTODY");
      inv.setAccountingSupplierParty(supplierWith("123456789"));

      MappingResult r = adapter(new EInvoiceFacadeMapper(broken), matcher(),
          new MultipartExtractionService()).map(inv);

      assertTrue(r.errors().stream().anyMatch(e ->
          e.code() == ErrorCode.MAPPING_ERROR
              && e.detail().contains("unhandled mapping exception")));
      assertNull(r.model());
      assertTrue(r.items().isEmpty());
      assertEquals("CUSTODY", r.feeType(),
          "the fee resolved before the mapper ran, and is not lost with it");
    }

    @Test
    @DisplayName("a null invoice is rejected outright")
    void nullInvoice() {
      assertThrows(NullPointerException.class, () -> adapter().map(null));
    }

    @Test
    @DisplayName("every collaborator is mandatory")
    void collaboratorsMandatory() {
      EInvoiceFacadeMapper m = new EInvoiceFacadeMapper(lookup());
      FeeTypeMatcher f = matcher();
      MultipartExtractionService e = new MultipartExtractionService();

      assertThrows(NullPointerException.class, () -> new EInvoiceMappingAdapter(null, f, e));
      assertThrows(NullPointerException.class, () -> new EInvoiceMappingAdapter(m, null, e));
      assertThrows(NullPointerException.class, () -> new EInvoiceMappingAdapter(m, f, null));
    }
  }

  // ── Attachments ───────────────────────────────────────────────────────────

  @Nested
  @DisplayName("attachments")
  class Attachments {

    @Test
    @DisplayName("a good embedded file comes back on the result")
    void goodAttachment() {
      Invoice inv = invoiceWithMarker("552120222_MARK_CUSTODY");
      inv.setAdditionalDocumentReference(List.of(embedded("invoice.pdf", "%PDF-1.7 body")));

      MappingResult r = adapter().map(inv);

      assertEquals(1, r.embeddedAttachments().size());
      assertEquals("invoice.pdf", r.embeddedAttachments().get(0).filename());
      assertTrue(r.errors().isEmpty());
    }

    @Test
    @DisplayName("a corrupt file is reported rather than silently dropped")
    void corruptAttachmentIsReported() {
      // The plain extraction drops anything that fails its checks, which makes a corrupt file
      // and an absent one indistinguishable downstream. They are not the same conversation to
      // have with a sender, so the adapter reads the detailed form.
      Invoice inv = invoiceWithMarker("552120222_MARK_CUSTODY");
      inv.setAdditionalDocumentReference(List.of(embedded("invoice.pdf", "not a pdf at all")));

      MappingResult r = adapter().map(inv);

      assertTrue(r.embeddedAttachments().isEmpty());
      assertTrue(r.errors().stream().anyMatch(e ->
          e.code() == ErrorCode.MISSING_ATTACHMENT && e.detail().contains("invoice.pdf")));
    }

    @Test
    @DisplayName("an extractor that throws is reported, and the model survives")
    void throwingExtractor() {
      MultipartExtractionService exploding = new MultipartExtractionService() {
        @Override public List<Result> extractDetailed(Invoice invoice) {
          throw new IllegalStateException("decoder blew up");
        }
      };
      MappingResult r = adapter(new EInvoiceFacadeMapper(lookup()), matcher(), exploding)
          .map(invoiceWithMarker("552120222_MARK_CUSTODY"));

      assertTrue(r.errors().stream().anyMatch(e ->
          e.code() == ErrorCode.MAPPING_ERROR
              && e.detail().contains("attachment extractor failed")));
      assertNotNull(r.model(), "the invoice itself mapped fine; only its files did not");
    }

    @Test
    @DisplayName("an invoice with no attachment block yields none, and no error")
    void noAttachments() {
      MappingResult r = adapter().map(invoiceWithMarker("552120222_MARK_CUSTODY"));
      assertTrue(r.embeddedAttachments().isEmpty());
      assertTrue(r.errors().isEmpty(),
          "whether an absent attachment is a problem is a rule's decision, not mapping's");
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private static AdditionalDocumentReference embedded(String filename, String content) {
    EmbeddedDocument doc = new EmbeddedDocument();
    doc.setFilename(filename);
    doc.setMimeCode("application/pdf");
    doc.setFile(Base64.getEncoder()
        .encodeToString(content.getBytes(StandardCharsets.UTF_8)));
    Attachment attachment = new Attachment();
    attachment.setEmbeddedDocumentBinaryObject(doc);
    AdditionalDocumentReference ref = new AdditionalDocumentReference();
    ref.setAttachment(attachment);
    return ref;
  }

  /**
   * A supplier whose SIREN is where the mapper actually looks for it: on the legal entity's
   * companyID, not on the endpoint. The endpoint carries the routing marker, which is a
   * different value entirely.
   */
  private static AccountingSupplierParty supplierWith(String siren) {
    SchemeID companyId = new SchemeID();
    companyId.setValue(siren);
    PartyLegalEntity legalEntity = new PartyLegalEntity();
    legalEntity.setCompanyId(companyId);
    Party party = new Party();
    party.setPartyLegalEntity(legalEntity);
    AccountingSupplierParty supplier = new AccountingSupplierParty();
    supplier.setParty(party);
    return supplier;
  }
}
