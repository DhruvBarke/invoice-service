package com.sg.domaininterface.rule.einvoice;

/**
 * Which side of the request supplied the attachments.
 *
 * <p>Recorded rather than inferred, because "no attachment" reads differently depending on the
 * answer. A sender that uploaded files and had them arrive empty is a different problem from a
 * sender that embedded nothing in the document, and only one of the two is worth telling them
 * about.
 */
public enum AttachmentChannel {

  /** Uploaded alongside the request. Wins whenever present. */
  MULTIPART,

  /** Base64-embedded in the e-invoice document itself. The fallback. */
  EINVOICE_BODY
}
