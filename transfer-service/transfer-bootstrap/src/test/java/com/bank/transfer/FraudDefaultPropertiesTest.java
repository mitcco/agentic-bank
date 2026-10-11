package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * T24: the defaults shipped in application.properties, read without starting the application
 * (so without database and without overrides).
 */
class FraudDefaultPropertiesTest {

  @Test
  void applicationPropertiesCarryTheTimeoutsOfTheContractAndTheDevelopmentUrl() {
    new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .run(context -> {
        assertThat(context.getEnvironment().getProperty("fraud.connect-timeout")).isEqualTo("1s");
        assertThat(context.getEnvironment().getProperty("fraud.read-timeout")).isEqualTo("3s");
        assertThat(context.getEnvironment().getProperty("fraud.base-url")).isEqualTo("http://localhost:8081");
      });
  }
}
