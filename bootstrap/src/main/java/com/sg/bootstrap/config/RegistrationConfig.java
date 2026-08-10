package com.sg.bootstrap.config;

import com.sg.alert.RegistrationAlertEmailBridge;
import com.sg.bootstrap.policy.ConfiguredAlertRoutingPolicy;
import com.sg.caching.CachingFeeTypeProvider;
import com.sg.domain.einvoice.InvoicePayableEnricher;
import com.sg.domain.einvoice.InvoiceRegistrationServiceImpl;
import com.sg.domaininterface.port.in.InvoiceRegistrationService;
import com.sg.domain.einvoice.rule.AttachmentPresentRule;
import com.sg.domain.einvoice.rule.BrokerageTradeFileRule;
import com.sg.domain.einvoice.rule.DuplicateInvoiceRule;
import com.sg.domain.einvoice.rule.LineItemsPresentRule;
import com.sg.domain.einvoice.rule.SettlementInstructionRule;
import com.sg.domain.einvoice.rule.ValidationRegistry;
import com.sg.domaininterface.model.einvoice.Business;
import com.sg.domaininterface.port.out.EInvoiceMappingPort;
import com.sg.domaininterface.port.out.AlertRoutingPolicy;
import com.sg.domaininterface.port.out.ExistingInvoicePayableLookup;
import com.sg.domaininterface.port.out.InvoiceEnrichmentPort;
import com.sg.domaininterface.port.out.InvoicePayableStore;
import com.sg.domaininterface.port.out.LifecycleEventPublisher;
import com.sg.domaininterface.port.out.RegistrationAlertNotifier;
import com.sg.domaininterface.port.out.PartyRegistrationLookup;
import com.sg.domaininterface.port.out.ProviderSetupLookup;
import com.sg.domaininterface.port.out.AlertEmailPort;
import com.sg.domaininterface.rule.einvoice.ValidationRule;
import com.sg.jpa.adapter.JdbcExistingInvoicePayableLookup;
import com.sg.domaininterface.port.thirdparty.BusinessCalendarService;
import com.sg.domaininterface.port.thirdparty.CurrencyConverterService;
import com.sg.domaininterface.port.thirdparty.FeeCategoryReferentialService;
import com.sg.domaininterface.port.thirdparty.SgDocReferentialService;
import com.sg.domaininterface.port.thirdparty.SsiReferentialService;
import com.sg.jpa.adapter.JdbcInvoicePayableStore;
import com.sg.jpa.adapter.JdbcProviderSetupLookup;
import com.sg.mapper.einvoice.EInvoiceFacadeMapper;
import com.sg.mapper.einvoice.EInvoiceMappingAdapter;
import com.sg.mapper.einvoice.FeeTypeMatcher;
import com.sg.domaininterface.port.out.FeeTypeProvider;
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
   * Adapts the fee-type referential to the mapper module's SPI, memoised for 30 minutes.
   *
   * <p>It used to read a {@code t_fee_type} table in this service's own schema — a local copy of
   * a referential someone else owns, and a copy is only ever as current as the last time
   * somebody remembered to refresh it. It comes over the referential API now, like the party and
   * document data.
   *
   * <p>The TTL matters for correctness of the matcher's index caching, not only for load: see
   * {@link CachingFeeTypeProvider}. Shorten it if fee types change more often than that.
   */
  @Bean
  public FeeTypeProvider feeTypeProvider(FeeCategoryReferentialService referential) {
    return new CachingFeeTypeProvider(
        referential::findAllFeeTypes, java.time.Duration.ofMinutes(30));
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

  /**
   * One bean, both ports.
   *
   * <p>Declared as the concrete type on purpose: {@link JdbcInvoicePayableStore} implements
   * {@link InvoicePayableStore} and {@link LifecycleEventPublisher}, and returning the class
   * rather than an interface is what lets Spring satisfy both from this single definition.
   *
   * <p>There used to be a second {@code @Bean} handing the very same instance back typed as
   * {@code LifecycleEventPublisher}. It looked harmless — one object, two names — but by-type
   * injection counts definitions, not identities: two candidates matched, Spring refused to
   * choose, and the context failed to start.
   *
   * <p>The two ports share an instance because they write the same row. Publishing a lifecycle
   * event updates the columns this store just inserted, so splitting them would put two
   * components on one table with no reason to agree about it.
   */
  @Bean
  public JdbcInvoicePayableStore invoicePayableStore(DataSource dataSource) {
    return new JdbcInvoicePayableStore(dataSource);
  }

  @Bean
  public ExistingInvoicePayableLookup existingInvoicePayableLookup(DataSource dataSource) {
    return new JdbcExistingInvoicePayableLookup(dataSource);
  }

  /**
   * Alert routing, resolved per business and fee category from configuration.
   *
   * <p>A scope with alerting off, or with no recipients, is dropped by the policy rather than by
   * this bean. That is the difference from before: the whole notifier used to collapse to a
   * no-op when the service-wide recipient list was empty, which silenced every business at once
   * because one of them had not been configured yet.
   */
  @Bean
  public AlertRoutingPolicy alertRoutingPolicy(RegistrationProperties props) {
    return new ConfiguredAlertRoutingPolicy(props);
  }

  @Bean
  public RegistrationAlertNotifier registrationAlertNotifier(
      AlertEmailPort emailPort, AlertRoutingPolicy routing) {
    return new RegistrationAlertEmailBridge(emailPort, routing);
  }

  @Bean
  public ProviderSetupLookup providerSetupLookup(DataSource dataSource) {
    return new JdbcProviderSetupLookup(dataSource);
  }

  /**
   * The fields no document can carry.
   *
   * <p>Kept out of the mapper deliberately — see {@link InvoicePayableEnricher}. The joint-venture
   * list is configuration rather than a constant: it changes when a venture is formed or wound up,
   * and neither should need a release.
   */
  @Bean
  public InvoicePayableEnricher invoicePayableEnricher(
      CurrencyConverterService rates,
      BusinessCalendarService calendar,
      ProviderSetupLookup providerSetup,
      RegistrationProperties props) {
    return new InvoicePayableEnricher(rates, calendar, providerSetup,
        props.getJointVentureEntities());
  }

  @Bean
  public ValidationRegistry validationRegistry(
      RegistrationProperties props,
      ExistingInvoicePayableLookup existingLookup,
      SsiReferentialService ssiReferential) {
    Map<String, ValidationRule> rulesById = Map.of(
        new DuplicateInvoiceRule(existingLookup).id(), new DuplicateInvoiceRule(existingLookup),
        new AttachmentPresentRule().id(), new AttachmentPresentRule(),
        new BrokerageTradeFileRule().id(), new BrokerageTradeFileRule(),
        new LineItemsPresentRule().id(), new LineItemsPresentRule(),
        new SettlementInstructionRule(ssiReferential).id(),
        new SettlementInstructionRule(ssiReferential));

    ValidationRegistry.Builder builder = ValidationRegistry.builder();
    for (Business business : Business.values()) {
      RegistrationProperties.BusinessConfig cfg = props.getBusinesses().get(business);
      if (cfg == null) {
        continue; // not configured — this business runs nothing
      }

      Set<String> businessRules = cfg.enabledRuleIds();
      for (Map.Entry<String, ValidationRule> rule : rulesById.entrySet()) {
        if (businessRules.contains(rule.getKey())) {
          builder.add(business, rule.getValue());
        }
      }

      // A fee category that configures rules replaces the business set for itself. The scope is
      // declared even when nothing in it is enabled, because an empty set and an absent one mean
      // different things — empty runs nothing, absent falls back to the business.
      cfg.getFeeCategories().forEach((feeCategory, feeConfig) -> {
        if (!feeConfig.overridesRules()) {
          return;
        }
        builder.addFeeCategoryScope(business, feeCategory);
        Set<String> feeRules = feeConfig.enabledRuleIds();
        for (Map.Entry<String, ValidationRule> rule : rulesById.entrySet()) {
          if (feeRules.contains(rule.getKey())) {
            builder.add(business, feeCategory, rule.getValue());
          }
        }
      });
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
      InvoiceEnrichmentPort enricher,
      SgDocReferentialService documentStore,
      ValidationRegistry rules,
      InvoicePayableStore store,
      LifecycleEventPublisher lifecyclePublisher,
      RegistrationAlertNotifier alertNotifier) {
    // Declared as the interface, built as the implementation: everything downstream — the
    // controller included — is injected with the interface and never learns which one it got.
    return new InvoiceRegistrationServiceImpl(
        mappingPort, enricher, documentStore, rules, store, lifecyclePublisher, alertNotifier);
  }
}
