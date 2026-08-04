package com.sg.bootstrap.config;

import com.sg.domain.alerting.RegistrationAlertEmailBridge;
import com.sg.domain.einvoice.InvoiceRegistrationServiceImpl;
import com.sg.domaininterface.service.InvoiceRegistrationService;
import com.sg.domain.einvoice.rule.AttachmentPresentRule;
import com.sg.domain.einvoice.rule.BrokerageTradeFileRule;
import com.sg.domain.einvoice.rule.DuplicateInvoiceRule;
import com.sg.domain.einvoice.rule.LineItemsPresentRule;
import com.sg.domain.einvoice.rule.ValidationRegistry;
import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.port.einvoice.EInvoiceMappingPort;
import com.sg.domaininterface.port.einvoice.ExistingInvoicePayableLookup;
import com.sg.domaininterface.port.einvoice.InvoicePayableStore;
import com.sg.domaininterface.port.einvoice.LifecycleEventPublisher;
import com.sg.domaininterface.port.einvoice.RegistrationAlertNotifier;
import com.sg.domaininterface.port.in.PartyRegistrationLookup;
import com.sg.domaininterface.port.out.AlertEmailPort;
import com.sg.domaininterface.rule.einvoice.ValidationRule;
import com.sg.jpa.adapter.JdbcExistingInvoicePayableLookup;
import com.sg.jpa.adapter.JdbcFeeTypeRepository;
import com.sg.jpa.adapter.JdbcInvoicePayableStore;
import com.sg.mapper.einvoice.EInvoiceFacadeMapper;
import com.sg.mapper.einvoice.EInvoiceMappingAdapter;
import com.sg.mapper.einvoice.FeeTypeMatcher;
import com.sg.mapper.einvoice.FeeTypeProvider;
import com.sg.mapper.einvoice.MultipartExtractionService;
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

  /**
   * The mapping stack, behind its port.
   *
   * <p>The three collaborators are assembled here and nowhere else. The use case below takes
   * the port, not these three, which is what keeps it from knowing that a fee-type matcher
   * exists at all.
   */
  @Bean
  public EInvoiceMappingPort eInvoiceMappingPort(
      EInvoiceFacadeMapper facadeMapper,
      FeeTypeMatcher feeTypeMatcher,
      MultipartExtractionService extractor) {
    return new EInvoiceMappingAdapter(facadeMapper, feeTypeMatcher, extractor);
  }

  @Bean
  public InvoiceRegistrationService invoiceRegistrationService(
      EInvoiceMappingPort mappingPort,
      ValidationRegistry rules,
      InvoicePayableStore store,
      LifecycleEventPublisher lifecyclePublisher,
      RegistrationAlertNotifier alertNotifier) {
    // Declared as the interface, built as the implementation: everything downstream — the
    // controller included — is injected with the interface and never learns which one it got.
    return new InvoiceRegistrationServiceImpl(
        mappingPort, rules, store, lifecyclePublisher, alertNotifier);
  }
}
