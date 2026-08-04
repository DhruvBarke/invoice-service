package com.sg.alert;

import com.sg.domaininterface.model.alerting.EmailAlertConfig;
import com.sg.domaininterface.rule.party.Servability;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code isImmediate} is package-private, so its test lives beside it rather than widening the
 * method's visibility to suit a test in another package.
 */
class EmailAlertConfigImmediacyTest {

  @ParameterizedTest
  @EnumSource(Servability.class)
  @DisplayName("only the configured servability triggers an immediate flush")
  void immediacyIsExact(Servability servability) {
    EmailAlertConfig blockingOnly =
        EmailAlertConfig.defaults(List.of("ops@example.com"), "[party]");
    assertEquals(servability == Servability.BLOCKING, blockingOnly.isImmediate(servability),
        "an immediate flush is reserved for the class of defect that stops processing");
  }

  @ParameterizedTest
  @EnumSource(Servability.class)
  @DisplayName("configuring SERVABLE flips which class flushes at once")
  void immediacyFollowsConfiguration(Servability servability) {
    EmailAlertConfig servableImmediate = new EmailAlertConfig(
        List.of("ops@example.com"), "[party]", java.time.Duration.ofMinutes(5),
        Servability.SERVABLE, 500, 3,
        java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(10));
    assertEquals(servability == Servability.SERVABLE, servableImmediate.isImmediate(servability));
  }
}
