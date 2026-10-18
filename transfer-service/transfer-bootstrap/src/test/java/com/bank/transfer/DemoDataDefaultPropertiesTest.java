package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The default shipped in application.properties for the demo data, read without starting the application
 * (so without database and without overrides).
 */
class DemoDataDefaultPropertiesTest {

  @Test
  void demoDataIsEnabledByDefault() {
    new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .run(context ->
        assertThat(context.getEnvironment().getProperty("transfer.demo-data.enabled")).isEqualTo("true"));
  }
}
