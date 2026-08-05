package com.sg.rest.codec;

import com.sg.domaininterface.model.invoice.Invoice;
import java.lang.reflect.Type;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

/**
 * Binds {@link Invoice} with {@link EInvoiceJsonCodec}'s mapper rather than Spring's default one.
 *
 * <p><b>Why this exists.</b> The e-invoice model is a vendored UBL structure with a wire contract
 * the default mapper does not know: {@code NoteEntry} arrives as the string {@code #AAI#remark}
 * and needs its own serde, properties bind by field name because the Lombok getters are not
 * always named after the fields they expose, UBL sends single-element collections as bare
 * scalars, and amounts must serialise plain rather than as {@code 1.23456E+5}.
 *
 * <p>All of that used to be applied because the controller read the document itself and called
 * the codec. When the endpoint changed to take the model in the request body, binding moved to
 * Spring's auto-configured mapper and quietly lost every one of those settings — the codec was
 * still there, still tested, and no longer on the path anything actually took.
 *
 * <p><b>Scoped to {@link Invoice} on purpose.</b> Making the codec's mapper the application's
 * mapper would fix the request and change every response with it: field-level visibility and
 * non-null inclusion are right for the vendored UBL model and are not obviously right for
 * {@code RegistrationOutcome}, which is a record designed to be read by an operator. One type,
 * one contract.
 */
public class EInvoiceHttpMessageConverter extends MappingJackson2HttpMessageConverter {

  public EInvoiceHttpMessageConverter() {
    super(EInvoiceJsonCodec.mapper());
  }

  @Override
  public boolean canRead(Type type, Class<?> contextClass, MediaType mediaType) {
    return isInvoice(type) && super.canRead(type, contextClass, mediaType);
  }

  @Override
  public boolean canWrite(Class<?> clazz, MediaType mediaType) {
    return Invoice.class.isAssignableFrom(clazz) && super.canWrite(clazz, mediaType);
  }

  /**
   * True only for {@link Invoice} itself.
   *
   * <p>Deliberately not {@code isAssignableFrom} on the raw type of any parameterised type: a
   * {@code List<Invoice>} or a wrapper containing one is not this contract, and claiming it here
   * would apply the vendored model's binding rules to something else's payload.
   */
  private static boolean isInvoice(Type type) {
    return type instanceof Class<?> clazz && Invoice.class.isAssignableFrom(clazz);
  }
}
