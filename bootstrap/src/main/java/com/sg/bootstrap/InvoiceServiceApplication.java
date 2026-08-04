package com.sg.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;

/**
 * The application.
 *
 * <p><b>Component scanning is listed, not inherited.</b> The default would scan from this class's
 * own package downwards and find nothing, because the beans live in sibling modules under
 * {@code com.sg.*}. Scanning all of {@code com.sg} instead would work today and quietly pick up
 * anything a future module adds — including in a module this one is not supposed to know the
 * internals of. Naming the packages keeps the wiring a decision rather than a side effect.
 *
 * <p>Only the two adapter packages and this module's own configuration are scanned. The domain
 * and the mapper are deliberately absent: their classes are plain constructor-injected objects
 * with no annotations, built by the {@code @Bean} methods in {@code com.sg.bootstrap.config}.
 * That is what lets them be constructed in a test with no container at all.
 */
@SpringBootApplication
@ComponentScan(basePackages = {
    "com.sg.bootstrap.config",   // the @Configuration classes that assemble everything
    "com.sg.rest",               // controllers
    "com.sg.jpa"                 // repository adapters
})
@ConfigurationPropertiesScan("com.sg.bootstrap.config")
public class InvoiceServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(InvoiceServiceApplication.class, args);
  }
}
