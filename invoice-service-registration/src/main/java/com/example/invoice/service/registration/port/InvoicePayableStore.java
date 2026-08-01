package com.example.invoice.service.registration.port;

import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoiceItem;
import com.example.invoice.mapper.einvoice.model.payableinvoice.InvoicePayableModel;
import com.example.invoice.service.registration.Business;
import com.example.invoice.service.registration.error.RegistrationOutcome;
import java.util.List;
import java.util.Objects;

/**
 * Port for persisting one row in {@code t_invoice_payable}.
 *
 * <p>Called once per registration by the orchestrator, whether the outcome is REGISTERED,
 * CANCELLED or INCOMPLETE. The row always exists — a failed registration is a data point, not
 * a discard. The concrete implementation lives in {@code invoice-service-app}
 * ({@code JdbcInvoicePayableStore}).
 */
public interface InvoicePayableStore {

  /** @return the newly assigned primary key ({@code id} column). */
  long persist(PersistRequest request);

  /**
   * @param business  resolved business, or {@code null} when parsing failed
   * @param feeId     resolved by {@link com.example.invoice.mapper.einvoice.FeeTypeMatcher}
   *                  or {@code null}
   * @param feeType   the referential fee-type value (post-matcher canonicalisation), or the
   *                  raw marker fee type when the matcher couldn't resolve, or {@code null}
   * @param source    hard-coded {@code "EINVOICE"} for this pipeline. Left extensible so future
   *                  sources (MANUAL, SGAI) can share the table.
   * @param model     the mapped payable model — may be {@code null} if mapping failed hard
   * @param items     line items, possibly empty
   * @param outcome   the decided status + lifecycle event + full error list
   */
  record PersistRequest(
      Business business,
      String feeId,
      String feeType,
      String source,
      InvoicePayableModel model,
      List<InvoiceItem> items,
      RegistrationOutcome outcome) {

    public PersistRequest {
      Objects.requireNonNull(outcome, "outcome");
      if (source == null || source.isBlank()) source = "EINVOICE";
      items = items == null ? List.of() : List.copyOf(items);
    }
  }
}
