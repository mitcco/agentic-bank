package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The defaults shipped in application.properties for the simulated clearing house, read without starting the
 * application (so without database and without overrides): it accepts everything and the outcomes are not
 * processed on their own unless someone turns it on.
 */
class ClearingDefaultPropertiesTest {

  @Test
  void clearingIsSimulatedAcceptingAndOutcomesProcessingIsOffByDefault() {
    new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .run(context -> {
        assertThat(context.getEnvironment().getProperty("clearing.simulator.mode")).isEqualTo("ACCEPT");
        assertThat(context.getEnvironment().getProperty("clearing.outcomes.enabled")).isEqualTo("false");
        assertThat(context.getEnvironment().getProperty("clearing.outcomes.fixed-delay")).isEqualTo("2s");
        assertThat(context.getEnvironment().getProperty("clearing.outcomes.initial-delay")).isEqualTo("5s");
      });
  }
}
