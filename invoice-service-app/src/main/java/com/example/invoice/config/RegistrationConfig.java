package com.example.invoice.config;

import com.example.invoice.mapper.einvoice.EInvoiceFacadeMapper;
import com.example.invoice.mapper.einvoice.FeeTypeMatcher;
import com.example.invoice.mapper.einvoice.FeeTypeProvider;
import com.example.invoice.mapper.einvoice.MultipartExtractionService;
import com.example.invoice.service.alerting.publish.AlertEmailPort;
import com.example.invoice.service.alerting.publish.RegistrationAlertEmailBridge;
import com.example.invoice.service.registration.Business;
import com.example.invoice.service.registration.InvoiceRegistrationService;
import com.example.invoice.service.registration.port.ExistingInvoicePayableLookup;
import com.example.invoice.service.registration.port.InvoicePayableStore;
import com.example.invoice.service.registration.port.LifecycleEventPublisher;
import com.example.invoice.service.registration.port.RegistrationAlertNotifier;
import com.example.invoice.service.registration.rule.AttachmentPresentRule;
import com.example.invoice.service.registration.rule.BrokerageTradeFileRule;
import com.example.invoice.service.registration.rule.DuplicateInvoiceRule;
import com.example.invoice.service.registration.rule.LineItemsPresentRule;
import com.example.invoice.service.registration.rule.ValidationRegistry;
import com.example.invoice.service.registration.rule.ValidationRule;
import com.example.invoice.service.domain.port.in.PartyRegistrationLookup;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for the registration pipeline. Wires the seven collaborators
 * {@link InvoiceRegistrationService} needs.
 *
 * <p>The {@link ValidationRegistry} is built from {@link RegistrationProperties} — each rule
 * has a stable id ({@link ValidationRule#id()}) and appears in the registry only for
 * businesses that flip it on in {@code application.yml}.
 */
@Configuration
@EnableConfigurationProperties(RegistrationProperties.class)
public class RegistrationConfig {

  /**
   * Adapts the fee-type table to the mapper module's SPI, memoised for 30 minutes.
   *
   * <p>The TTL matters for correctness of the matcher's index caching, not just for load: see
   * {@link CachingFeeTypeProvider}. Shorten it if fee types change more often than that.
   */
  @Bean
  public FeeTypeProvider feeTypeProvider(DataSource dataSource) {
    JdbcFeeTypeRepository repo = new JdbcFeeTypeRepository(dataSource);
    return new CachingFeeTypeProvider(repo::findAllFeeTypes, java.time.Duration.ofMinutes(30));
  }

  @Bean
  public FeeTypeMatcher feeTypeMatcher(FeeTypeProvider feeTypeProvider) {
    return new FeeTypeMatcher(feeTypeProvider);
  }

  @Bean
  public MultipartExtractionService multipartExtractionService() {
    return new MultipartExtractionService();
  }

  @Bean
  public EInvoiceFacadeMapper eInvoiceFacadeMapper(PartyRegistrationLookup lookup) {
    return new EInvoiceFacadeMapper(lookup);
  }

  @Bean
  public JdbcInvoicePayableStore invoicePayableStore(DataSource dataSource) {
    return new JdbcInvoicePayableStore(dataSource);
  }

  /** Same instance is the {@link InvoicePayableStore} and the {@link LifecycleEventPublisher}. */
  @Bean
  public LifecycleEventPublisher lifecycleEventPublisher(JdbcInvoicePayableStore store) {
    return store;
  }

  @Bean
  public ExistingInvoicePayableLookup existingInvoicePayableLookup(DataSource dataSource) {
    return new JdbcExistingInvoicePayableLookup(dataSource);
  }

  @Bean
  public RegistrationAlertNotifier registrationAlertNotifier(
      AlertEmailPort emailPort, RegistrationProperties props) {
    // Empty recipient list → drop to a no-op so misconfigured environments don't crash on
    // every failed registration attempt. Ops sees "no recipients" in the DB row error_codes
    // JSON instead.
    List<String> recipients = props.getAlert().getRecipients();
    if (recipients == null || recipients.isEmpty()) {
      return RegistrationAlertNotifier.none();
    }
    return new RegistrationAlertEmailBridge(
        emailPort, recipients, props.getAlert().getSubjectPrefix());
  }

  @Bean
  public ValidationRegistry validationRegistry(
      RegistrationProperties props,
      ExistingInvoicePayableLookup existingLookup) {
    Map<String, ValidationRule> rulesById = Map.of(
        new DuplicateInvoiceRule(existingLookup).id(), new DuplicateInvoiceRule(existingLookup),
        new AttachmentPresentRule().id(), new AttachmentPresentRule(),
        new BrokerageTradeFileRule().id(), new BrokerageTradeFileRule(),
        new LineItemsPresentRule().id(), new LineItemsPresentRule());

    ValidationRegistry.Builder builder = ValidationRegistry.builder();
    for (Business business : Business.values()) {
      RegistrationProperties.BusinessRules cfg = props.getBusinesses().get(business);
      if (cfg == null) continue; // no rules configured — this business gets the empty set
      Set<String> enabled = cfg.enabledRuleIds();
      for (Map.Entry<String, ValidationRule> e : rulesById.entrySet()) {
        if (enabled.contains(e.getKey())) {
          builder.add(business, e.getValue());
        }
      }
    }
    return builder.build();
  }

  @Bean
  public InvoiceRegistrationService invoiceRegistrationService(
      EInvoiceFacadeMapper facadeMapper,
      FeeTypeMatcher feeTypeMatcher,
      MultipartExtractionService extractor,
      ValidationRegistry rules,
      InvoicePayableStore store,
      LifecycleEventPublisher lifecyclePublisher,
      RegistrationAlertNotifier alertNotifier) {
    return new InvoiceRegistrationService(
        facadeMapper, feeTypeMatcher, extractor, rules, store, lifecyclePublisher, alertNotifier);
  }
}
