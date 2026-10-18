package com.bank.transfer.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transfer.application.port.out.ClearingHouseOutPort;
import com.bank.transfer.clearing.port.out.clearing.impl.SimulatedClearingHouseOutPortImpl;
import com.bank.transfer.clearing.port.out.clearing.impl.SimulatedClearingModeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The wiring of the simulated clearing house: one port, the configured mode, an unreadable mode fails the startup,
 * two WARN at startup. Same style as NotificationPublisherConfigTest.
 */
@ExtendWith(OutputCaptureExtension.class)
class ClearingConfigTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
    // as in the real application: an unresolved ${...} must fail, not stay as a literal string
    .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
    .withUserConfiguration(ClearingConfig.class)
    // Boot's conversion service is what turns the text of the property into the enum for @Value
    .withInitializer(context -> context.getBeanFactory()
      .setConversionService(ApplicationConversionService.getSharedInstance()));

  @Test
  void theSimulatorIsTheOnlyClearingHousePortWithTheConfiguredMode() {
    runner.withPropertyValues("clearing.simulator.mode=REJECT")
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context.getBeansOfType(ClearingHouseOutPort.class)).hasSize(1);
        assertThat(context.getBeansOfType(SimulatedClearingHouseOutPortImpl.class)).hasSize(1);
        assertThat(context.getBean(SimulatedClearingHouseOutPortImpl.class).mode())
          .isEqualTo(SimulatedClearingModeEnum.REJECT);
      });
  }

  @Test
  void startupWarnsThatTheClearingHouseIsSimulated(CapturedOutput output) {
    runner.withPropertyValues("clearing.simulator.mode=ACCEPT", "clearing.outcomes.enabled=true")
      .run(context -> assertThat(context).hasNotFailed());

    assertThat(output.getAll()).containsIgnoringCase("simulated");
  }

  @Test
  void startupWarnsWhenOutcomesProcessingIsDisabled(CapturedOutput output) {
    runner.withPropertyValues("clearing.simulator.mode=ACCEPT")
      .run(context -> assertThat(context).hasNotFailed());

    assertThat(output.getAll()).contains("clearing.outcomes.enabled");
  }

  @Test
  void startupWarnsWhenOutcomesProcessingIsExplicitlyDisabled(CapturedOutput output) {
    runner.withPropertyValues("clearing.simulator.mode=ACCEPT", "clearing.outcomes.enabled=false")
      .run(context -> assertThat(context).hasNotFailed());

    assertThat(output.getAll()).contains("clearing.outcomes.enabled");
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "TRUE"})
  void noDisabledWarnWhenEnabled(String value, CapturedOutput output) {
    runner.withPropertyValues("clearing.simulator.mode=ACCEPT", "clearing.outcomes.enabled=" + value)
      .run(context -> assertThat(context).hasNotFailed());

    assertThat(output.getAll()).doesNotContain("clearing.outcomes.enabled");
  }

  @Test
  void unknownOrMissingModeFailsTheStartup() {
    runner.withPropertyValues("clearing.simulator.mode=MAYBE")
      .run(context -> assertThat(context).hasFailed());
    runner.run(context -> assertThat(context).hasFailed());
  }

  @ParameterizedTest
  @EnumSource(value = SimulatedClearingModeEnum.class, names = {"TIMEOUT_THEN_ACCEPT", "TIMEOUT_THEN_REJECT"})
  void timeoutModesAreAccepted(SimulatedClearingModeEnum mode) {
    runner.withPropertyValues("clearing.simulator.mode=" + mode.name())
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context.getBean(SimulatedClearingHouseOutPortImpl.class).mode()).isEqualTo(mode);
      });
  }
}
