package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The routing default shipped in application.properties, read without starting the application.
 */
class RoutingDefaultPropertiesTest {

  @Test
  void ourBankCodeIs05428ByDefault() {
    new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .run(context -> assertThat(context.getEnvironment().getProperty("transfer.bank-code")).isEqualTo("05428"));
  }
}
