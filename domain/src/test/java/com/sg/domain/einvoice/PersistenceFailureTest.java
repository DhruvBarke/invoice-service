package com.sg.domain.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sg.domain.einvoice.rule.ValidationRegistry;
import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.model.einvoice.EInvoiceMarker;
import com.sg.domaininterface.model.einvoice.error.ErrorCode;
import com.sg.domaininterface.model.invoice.ExtractedAttachment;
import com.sg.domaininterface.model.invoice.Invoice;
import com.sg.domaininterface.model.payableinvoice.InvoicePayable;
import com.sg.domaininterface.model.payableinvoice.InvoicePayableModel;
import com.sg.domaininterface.port.in.RegistrationFailedException;
import com.sg.domaininterface.port.out.EInvoiceMappingPort;
import com.sg.domaininterface.port.out.EInvoiceMappingPort.MappingResult;
import com.sg.domaininterface.port.out.InvoicePayableStore;
import com.sg.domaininterface.port.out.LifecycleEventPublisher;
import com.sg.domaininterface.port.out.RegistrationAlertNotifier;
import com.sg.domaininterface.port.out.RegistrationAlertNotifier.RegistrationAlert;
import com.sg.domaininterface.port.thirdparty.SgDocReferentialService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What happens when the row itself cannot be written.
 *
 * <p>Every other failure in this pipeline ends with a persisted row carrying its own reason. This
 * one cannot: the row is what failed. So it is the single case where the use case throws rather
 * than returning an outcome — returning one would tell the caller the invoice was stored, and
 * nothing would ever resend it.
 */
class PersistenceFailureTest {

  private static final class SilentDocs implements SgDocReferentialService {
    @Override public String upload(ExtractedAttachment a, String ref) { return "DOC-1"; }
    @Override public ExtractedAttachment download(String id) { return null; }
  }

  private static EInvoiceMappingPort cleanMapping() {
    InvoicePayableModel model = new InvoicePayableModel();
    model.setInvoicePayable(new InvoicePayable());
    return inv -> new MappingResult(model, List.of(), List.of(),
        new EInvoiceMarker("552120222", Business.MARK, "CUSTODY", "552120222_MARK_CUSTODY"),
        "F01", "CUSTODY", List.of());
  }

  private static Invoice invoice() {
    Invoice invoice = new Invoice();
    invoice.setId("SUP-INV-1");
    return invoice;
  }

  private static InvoiceRegistrationServiceImpl service(InvoicePayableStore store,
                                                        List<RegistrationAlert> alerts) {
    return new InvoiceRegistrationServiceImpl(
        cleanMapping(), new SilentDocs(), ValidationRegistry.builder().build(), store,
        (LifecycleEventPublisher) e -> { }, alerts::add);
  }

  @Test
  @DisplayName("a store failure throws rather than returning a misleading outcome")
  void storeFailureThrows() {
    IllegalStateException cause = new IllegalStateException("connection pool exhausted");
    List<RegistrationAlert> alerts = new ArrayList<>();

    RegistrationFailedException thrown = assertThrows(RegistrationFailedException.class,
        () -> service(req -> { throw cause; }, alerts).register(invoice(), List.of()));

    assertSame(cause, thrown.getCause());
    assertTrue(thrown.getMessage().contains("SUP-INV-1"),
        "the message names the invoice, so the failure points at something specific");
  }

  @Test
  @DisplayName("the outcome travels on the exception, since there is no row to hold it")
  void outcomeTravelsOnTheException() {
    RegistrationFailedException thrown = assertThrows(RegistrationFailedException.class,
        () -> service(req -> { throw new IllegalStateException("disk full"); }, new ArrayList<>())
            .register(invoice(), List.of()));

    assertTrue(thrown.outcome().errors().stream()
            .anyMatch(e -> e.code() == ErrorCode.PERSISTENCE_FAILED),
        "the caller learns why, even though nothing was written");
  }

  @Test
  @DisplayName("an operator is alerted, with no row id to quote")
  void alertIsSentWithoutARowId() {
    List<RegistrationAlert> alerts = new ArrayList<>();

    assertThrows(RegistrationFailedException.class,
        () -> service(req -> { throw new IllegalStateException("disk full"); }, alerts)
            .register(invoice(), List.of()));

    assertEquals(1, alerts.size(), "nobody would otherwise know the invoice vanished");
    assertNull(alerts.get(0).invoicePayableId(),
        "there is no row, and inventing an id would send someone looking for one");
    assertTrue(alerts.get(0).outcome().errors().stream()
        .anyMatch(e -> e.code() == ErrorCode.PERSISTENCE_FAILED));
  }

  @Test
  @DisplayName("a successful store returns normally, so the throw is not the common path")
  void successReturnsAnOutcome() {
    List<RegistrationAlert> alerts = new ArrayList<>();
    UUID id = UUID.randomUUID();

    assertEquals(com.sg.domaininterface.model.einvoice.error.RegistrationOutcome.Status.REGISTERED,
        service(req -> id, alerts).register(invoice(), List.of()).status());
    assertTrue(alerts.isEmpty(), "a clean registration alerts nobody");
  }
}
