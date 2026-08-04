package com.sg.domaininterface.port.einvoice;

import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.model.einvoice.error.RegistrationOutcome;
import com.sg.domaininterface.model.payableinvoice.InvoiceDocumentPayable;
import com.sg.domaininterface.model.payableinvoice.InvoiceItem;
import com.sg.domaininterface.model.payableinvoice.InvoicePayableModel;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Port for persisting one registration.
 *
 * <p>Called once per registration by the orchestrator, whatever the outcome — a failed
 * registration is a data point, not a discard. The concrete implementation
 * ({@code JdbcInvoicePayableStore}, in the app module) spreads the request across the three
 * shared tables, which correlate on {@code invoice_reference} and are not foreign-keyed:
 *
 * <ul>
 *   <li>{@code t_invoice_payable} — the envelope, with the {@code InvoicePayable} as jsonb</li>
 *   <li>{@code t_invoice_items} — one row per line, correlating on {@code inv_reference_sg}</li>
 *   <li>{@code t_invoice_document_payable} — one row per document, metadata only</li>
 * </ul>
 *
 * <p><b>The implementation mints {@code invoiceReference} and the primary keys.</b> The mapper
 * leaves the reference null, because the incoming e-invoice's id is unique only within the
 * issuing supplier — that value is the {@code providerReference} instead. The store assigns the
 * reference from a database sequence and writes it back onto {@link PersistRequest#model()},
 * its items and its documents, so everything downstream of persistence quotes the value the row
 * actually carries.
 */
public interface InvoicePayableStore {

  /** @return the {@code t_invoice_payable} primary key. */
  UUID persist(PersistRequest request);

  /**
   * @param business    resolved business, or {@code null} when parsing failed
   * @param feeId       resolved by {@link com.sg.mapper.einvoice.FeeTypeMatcher},
   *                    or {@code null}
   * @param feeType     the referential's canonical fee type when the matcher resolved it, else
   *                    the raw marker tail, else {@code null}
   * @param invoiceFlow the producer discriminator written to {@code invoice_flow}. Always
   *                    {@code "EINVOICE"} from this pipeline; the column is shared with the
   *                    manual and SGAi producers, which is exactly what makes it the right
   *                    place to record which one wrote the row.
   * @param model       the mapped envelope — {@code null} when mapping failed outright. Its
   *                    {@code id} and {@code invoiceReference} are populated by the store.
   * @param items       invoice lines, possibly empty
   * @param documents   document metadata from both channels, distinguished by
   *                    {@link InvoiceDocumentPayable#getIncomingLine()}. Metadata only — the
   *                    bytes go to SGDoc and come back as an {@code sgDocId}.
   * @param outcome     the decided status, lifecycle event and full error list
   */
  record PersistRequest(
      Business business,
      String feeId,
      String feeType,
      String invoiceFlow,
      InvoicePayableModel model,
      List<InvoiceItem> items,
      List<InvoiceDocumentPayable> documents,
      RegistrationOutcome outcome) {

    public PersistRequest {
      Objects.requireNonNull(outcome, "outcome");
      if (invoiceFlow == null || invoiceFlow.isBlank()) {
        invoiceFlow = "EINVOICE";
      }
      items = items == null ? List.of() : List.copyOf(items);
      documents = documents == null ? List.of() : List.copyOf(documents);
    }
  }
}
