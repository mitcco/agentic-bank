package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The defaults shipped in application.properties for the gauge of the pending outbox rows (transfer-14, D4), read
 * without starting the application (so without database and without overrides). The variable
 * TRANSFER_OUTBOX_METRICS_ENABLED must not be set on the machine.
 */
class OutboxMetricsDefaultPropertiesTest {

  @Test
  void defaultsAreEnabledFifteenSecondsAndFiveSeconds() {
    new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .run(context -> {
        assertThat(context.getEnvironment().getProperty("transfer.metrics.outbox.enabled")).isEqualTo("true");
        assertThat(context.getEnvironment().getProperty("transfer.metrics.outbox.fixed-delay")).isEqualTo("15s");
        assertThat(context.getEnvironment().getProperty("transfer.metrics.outbox.initial-delay")).isEqualTo("5s");
      });
  }
}
