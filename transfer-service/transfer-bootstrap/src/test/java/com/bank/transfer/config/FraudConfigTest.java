package com.bank.transfer.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.port.out.fraud.impl.HttpFraudCheckOutPortImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FraudConfigTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
    // as in the real application: an unresolved ${...} must fail, not stay as a literal string
    .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
    .withUserConfiguration(FraudConfig.class)
    // Boot's conversion service is what turns "1s" into a Duration for @Value
    .withInitializer(context -> context.getBeanFactory()
      .setConversionService(ApplicationConversionService.getSharedInstance()));

  @Test
  void withoutProfileTheHttpAdapterIsTheOnlyFraudCheckBean() {
    runner.withPropertyValues(
        "fraud.base-url=http://fraud.test:8081",
        "fraud.connect-timeout=1s",
        "fraud.read-timeout=3s")
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context.getBeansOfType(FraudCheckOutPort.class)).hasSize(1);
        assertThat(context.getBean(FraudCheckOutPort.class)).isInstanceOf(HttpFraudCheckOutPortImpl.class);
      });
  }

  @Test
  void missingBaseUrlFailsTheStartup() {
    runner.withPropertyValues("fraud.connect-timeout=1s", "fraud.read-timeout=3s")
      .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void emptyBaseUrlFailsTheStartup() {
    runner.withPropertyValues("fraud.base-url=", "fraud.connect-timeout=1s", "fraud.read-timeout=3s")
      .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void unreadableReadTimeoutFailsTheStartup() {
    runner.withPropertyValues("fraud.base-url=http://fraud.test:8081", "fraud.connect-timeout=1s",
        "fraud.read-timeout=abc")
      .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void missingTimeoutsFailTheStartup() {
    runner.withPropertyValues("fraud.base-url=http://fraud.test:8081", "fraud.read-timeout=3s")
      .run(context -> assertThat(context).hasFailed());
    runner.withPropertyValues("fraud.base-url=http://fraud.test:8081", "fraud.connect-timeout=1s")
      .run(context -> assertThat(context).hasFailed());
  }
}
