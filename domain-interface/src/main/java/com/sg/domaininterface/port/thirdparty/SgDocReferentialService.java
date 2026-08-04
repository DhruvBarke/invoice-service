package com.sg.domaininterface.port.thirdparty;

import com.sg.domaininterface.model.invoice.ExtractedAttachment;

/**
 * Document storage, from the referential.
 *
 * <p>A third-party port. {@code t_invoice_document_payable} holds metadata and an
 * {@code sg_doc_id}; the content itself lives here. That split is why the document table has no
 * content column, and why a row with a null handle is meaningful rather than broken — it records
 * that a document arrived and is not yet retrievable.
 */
public interface SgDocReferentialService {

  /**
   * Store a document and return the handle.
   *
   * @param attachment       the file, with its bytes
   * @param invoiceReference SG's reference for the invoice it belongs to, so the document is
   *                         findable from the invoice without a second index
   * @return the handle to record in {@code sg_doc_id}. Never null — an upload that returned no
   *         handle stored nothing findable, and is reported as a failure instead.
   * @throws ReferentialUnavailableException when the upload could not be completed. The caller
   *         records the document row with a null handle rather than discarding the registration:
   *         a document that arrived and failed to upload is a different problem from one that
   *         was never sent, and only the first is worth retrying.
   */
  String upload(ExtractedAttachment attachment, String invoiceReference);

  /**
   * Fetch a document's content by handle.
   *
   * @param sgDocId the handle previously returned by {@link #upload}
   * @return the document, with its bytes
   * @throws ReferentialUnavailableException when it could not be retrieved
   */
  ExtractedAttachment download(String sgDocId);
}
