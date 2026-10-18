package com.bank.transfer.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transfer.domain.BankCode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

/**
 * The BankCode bean of the router: it comes from transfer.bank-code; a missing or invalid value fails the startup.
 * Same style as ClearingConfigTest.
 */
class TransferRoutingConfigTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
    .withUserConfiguration(TransferRoutingConfig.class)
    .withInitializer(context -> context.getBeanFactory()
      .setConversionService(ApplicationConversionService.getSharedInstance()));

  @Test
  void theConfiguredBankCodeIsTheBean() {
    runner.withPropertyValues("transfer.bank-code=03069")
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context.getBeansOfType(BankCode.class)).hasSize(1);
        assertThat(context.getBean(BankCode.class)).isEqualTo(new BankCode("03069"));
      });
  }

  @Test
  void aMissingBankCodeFailsTheStartup() {
    runner.run(context -> assertThat(context).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ABC", "abcde", "05428 ", ""})
  void anInvalidBankCodeFailsTheStartup(String value) {
    // a map property source keeps the trailing blank, which withPropertyValues would trim
    runner.withInitializer(context -> context.getEnvironment().getPropertySources()
        .addFirst(new MapPropertySource("bank-code", Map.of("transfer.bank-code", value))))
      .run(context -> assertThat(context).hasFailed());
  }
}
