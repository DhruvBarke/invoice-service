package com.sg.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * That no port has two bean definitions competing to satisfy it.
 *
 * <p><b>Why this exists.</b> {@code RegistrationConfig} declared the payable store twice — once
 * as the concrete {@code JdbcInvoicePayableStore}, once as a {@code LifecycleEventPublisher}
 * handing back the very same instance. One object, two names, and it looked harmless. By-type
 * injection counts definitions rather than identities, so two candidates matched, Spring refused
 * to choose, and the context failed to start.
 *
 * <p>Nothing in the suite noticed. Every test builds its collaborators directly, which is what
 * keeps them fast and container-free — and means none of them would ever have found this. The
 * cheapest thing that does is to read the {@code @Bean} methods and check the types they promise,
 * which needs reflection and no context at all.
 *
 * <p><b>What this does not prove.</b> It reads declared return types, so it cannot see a bean
 * contributed by a starter's auto-configuration, nor one registered programmatically. It catches
 * the case that actually occurred: two {@code @Bean} methods in this application's own
 * configuration answering to one port.
 */
class BeanDefinitionUniquenessTest {

  /** Every configuration class the application scans. */
  private static final List<Class<?>> CONFIGURATIONS = List.of(
      RegistrationConfig.class,
      PartyRegistrationConfig.class,
      ReferentialConfig.class,
      OpenApiConfig.class,
      WebConfig.class);

  /**
   * The packages where by-type injection actually happens.
   *
   * <p>Scoped to the ports because those are what constructors ask for. Two beans of, say,
   * {@code String} would be a different question entirely and is not what this is looking for.
   */
  private static final String PORT_PACKAGE = "com.sg.domaininterface.port";

  @Test
  @DisplayName("each domain port is satisfied by exactly one bean definition")
  void noPortHasCompetingDefinitions() {
    Map<Class<?>, List<String>> byPort = new TreeMap<>(
        (a, b) -> a.getName().compareTo(b.getName()));

    for (Class<?> config : CONFIGURATIONS) {
      for (Method method : config.getDeclaredMethods()) {
        if (!method.isAnnotationPresent(Bean.class)) {
          continue;
        }
        // The declared return type is what Spring registers, and every interface it carries is
        // a type this definition can be injected as — which is exactly how the duplicate arose.
        for (Class<?> port : portsSatisfiedBy(method.getReturnType())) {
          String marker = method.isAnnotationPresent(Primary.class) ? " (@Primary)" : "";
          byPort.computeIfAbsent(port, k -> new ArrayList<>())
              .add(config.getSimpleName() + "#" + method.getName() + marker);
        }
      }
    }

    Map<Class<?>, List<String>> competing = new LinkedHashMap<>();
    byPort.forEach((port, definitions) -> {
      if (definitions.size() <= 1) {
        return;
      }
      // Exactly one @Primary resolves the ambiguity, and is the right answer where the several
      // beans are all wanted — a decorator and the thing it decorates, say. More than one
      // @Primary resolves nothing, and none at all is the failure this test exists for.
      long primaries = definitions.stream().filter(d -> d.endsWith("(@Primary)")).count();
      if (primaries != 1) {
        competing.put(port, definitions);
      }
    });

    assertTrue(competing.isEmpty(), () -> {
      StringBuilder message = new StringBuilder(
          "more than one @Bean definition satisfies a port, so by-type injection cannot "
              + "choose and the context will not start:\n");
      competing.forEach((port, definitions) ->
          message.append("  ").append(port.getName()).append("\n    <- ")
              .append(String.join("\n    <- ", definitions)).append('\n'));
      return message.toString();
    });
  }

  @Test
  @DisplayName("the payable store still answers to both of its ports")
  void oneStoreSatisfiesBothPorts() {
    // The fix removed a bean rather than adding a qualifier, so this is the property that had to
    // survive: a single definition still covers the store and the publisher. Losing it would
    // start the context and fail at injection instead.
    List<Class<?>> ports = portsSatisfiedBy(
        methodNamed(RegistrationConfig.class, "invoicePayableStore").getReturnType());

    List<String> names = ports.stream().map(Class::getSimpleName).sorted().toList();
    assertTrue(names.contains("InvoicePayableStore"), () -> "got " + names);
    assertTrue(names.contains("LifecycleEventPublisher"), () -> "got " + names);
  }

  @Test
  @DisplayName("the gated notifier is the primary one, not the raw publisher")
  void gatedNotifierWinsOverThePublisher() {
    // Both are AlertNotifiers and both are wanted: one sends, the other wraps it in the
    // configured switches. If the wrong one were injected the alerting switches would be
    // bypassed silently, which is worse than the context failing to start.
    assertTrue(methodNamed(PartyRegistrationConfig.class, "alertNotifier")
        .isAnnotationPresent(Primary.class));
    assertFalse(methodNamed(PartyRegistrationConfig.class, "emailAlertPublisher")
        .isAnnotationPresent(Primary.class));
  }

  @Test
  @DisplayName("every configuration class contributes at least one bean")
  void configurationsAreNotEmpty() {
    // A configuration that stops declaring beans is usually a merge that went wrong, and the
    // application starts perfectly well without whatever it used to provide.
    for (Class<?> config : CONFIGURATIONS) {
      long beans = List.of(config.getDeclaredMethods()).stream()
          .filter(m -> m.isAnnotationPresent(Bean.class))
          .count();
      if (config == WebConfig.class) {
        // WebConfig customises the converter list through WebMvcConfigurer rather than by
        // declaring anything.
        assertEquals(0, beans, "WebConfig is expected to contribute no beans");
        continue;
      }
      assertFalse(beans == 0, config.getSimpleName() + " declares no @Bean methods");
    }
  }

  /** Every {@code com.sg.domaininterface.port} interface a bean of this type can be injected as. */
  private static List<Class<?>> portsSatisfiedBy(Class<?> beanType) {
    List<Class<?>> ports = new ArrayList<>();
    collectInterfaces(beanType, ports);
    return ports.stream()
        .filter(c -> c.getName().startsWith(PORT_PACKAGE))
        .distinct()
        .toList();
  }

  private static void collectInterfaces(Class<?> type, List<Class<?>> into) {
    if (type == null || type == Object.class) {
      return;
    }
    if (type.isInterface()) {
      into.add(type);
    }
    for (Class<?> parent : type.getInterfaces()) {
      collectInterfaces(parent, into);
    }
    collectInterfaces(type.getSuperclass(), into);
  }

  private static Method methodNamed(Class<?> owner, String name) {
    for (Method m : owner.getDeclaredMethods()) {
      if (m.getName().equals(name)) {
        return m;
      }
    }
    throw new AssertionError(owner.getSimpleName() + " has no method " + name);
  }
}
