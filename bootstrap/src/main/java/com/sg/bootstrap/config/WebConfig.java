package com.sg.bootstrap.config;

import com.sg.rest.codec.EInvoiceHttpMessageConverter;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Puts the e-invoice converter in front of Spring's default JSON one.
 *
 * <p>Order is the whole point. Spring picks the first converter that claims the type, and its
 * own {@code MappingJackson2HttpMessageConverter} claims everything — so a converter added at
 * the end would never be reached. Registered at index 0, this one takes {@link
 * com.sg.domaininterface.model.invoice.Invoice} and declines everything else, leaving the
 * defaults to handle responses.
 *
 * <p>{@code extendMessageConverters} rather than {@code configureMessageConverters}: the latter
 * replaces the whole list, which would take the defaults with it and leave nothing able to
 * serialise the response.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  @Override
  public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    converters.add(0, new EInvoiceHttpMessageConverter());
  }
}
