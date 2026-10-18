package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The defaults shipped in application.properties for the reconciliation of the stale external transfers, read
 * without starting the application (so without database and without overrides).
 */
class ClearingReconcileDefaultPropertiesTest {

  @Test
  void reconciliationDefaultsAreFiveMinutesThirtySecondsTenSecondsAndFifty() {
    new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .run(context -> {
        assertThat(context.getEnvironment().getProperty("clearing.reconcile.stale-after")).isEqualTo("5m");
        assertThat(context.getEnvironment().getProperty("clearing.reconcile.fixed-delay")).isEqualTo("30s");
        assertThat(context.getEnvironment().getProperty("clearing.reconcile.initial-delay")).isEqualTo("10s");
        assertThat(context.getEnvironment().getProperty("clearing.reconcile.batch-size")).isEqualTo("50");
      });
  }
}
