package com.bank.transfer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.bank.transfer.application.port.in.ProcessClearingOutcomesInPort;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The scheduler of the clearing outcomes: off unless clearing.outcomes.enabled=true, never throws, logs only the
 * simple class name of an exception. Same style as NotificationPublisherConfigTest.
 */
@ExtendWith(OutputCaptureExtension.class)
class ClearingOutcomesSchedulingConfigTest {

  private static final String CAUSE_MARKER = "SENSITIVE-CAUSE-MESSAGE";

  /** The in port, faked by hand: counts the calls, the result of each call is scripted by the test. */
  static final class FakeProcessPort implements ProcessClearingOutcomesInPort {
    final AtomicInteger calls = new AtomicInteger();
    volatile IntUnaryOperator behaviour = call -> 0;

    @Override
    public int processOutcomes() {
      return behaviour.applyAsInt(calls.incrementAndGet());
    }
  }

  @Configuration
  static class CollaboratorsConfig {

    @Bean
    FakeProcessPort fakeProcessPort() {
      return new FakeProcessPort();
    }
  }

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
    // as in the real application: an unresolved ${...} must fail, not stay as a literal string
    .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
    .withUserConfiguration(CollaboratorsConfig.class, ClearingOutcomesSchedulingConfig.class)
    // Boot's conversion service is what turns "1s" into a Duration
    .withInitializer(context -> context.getBeanFactory()
      .setConversionService(ApplicationConversionService.getSharedInstance()));

  private ApplicationContextRunner enabled() {
    return runner.withPropertyValues(
      "clearing.outcomes.enabled=true",
      "clearing.outcomes.fixed-delay=1h",
      "clearing.outcomes.initial-delay=1h");
  }

  @Test
  void noSchedulerWithoutTheProperty() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBeansOfType(ClearingOutcomesScheduler.class)).isEmpty();
      // nobody calls the port in
      assertThat(context.getBean(FakeProcessPort.class).calls.get()).isZero();
    });
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "no", "false"})
  void anyValueOtherThanTrueLeavesItOff(String value) {
    runner.withPropertyValues("clearing.outcomes.enabled=" + value)
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context.getBeansOfType(ClearingOutcomesScheduler.class)).isEmpty();
      });
  }

  @Test
  void enabledSchedulerCallsThePortRepeatedly() {
    runner.withPropertyValues("clearing.outcomes.enabled=true", "clearing.outcomes.fixed-delay=50ms",
        "clearing.outcomes.initial-delay=0")
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context.getBeansOfType(ClearingOutcomesScheduler.class)).hasSize(1);
        FakeProcessPort port = context.getBean(FakeProcessPort.class);

        await().atMost(5, TimeUnit.SECONDS).until(() -> port.calls.get() >= 2);
      });
  }

  @Test
  void aFailingRoundDoesNotEscapeAndOnlyTheClassNameIsLogged(CapturedOutput output) {
    enabled().run(context -> {
      FakeProcessPort port = context.getBean(FakeProcessPort.class);
      port.behaviour = call -> {
        throw new IllegalStateException(CAUSE_MARKER);
      };
      ClearingOutcomesScheduler scheduler = context.getBean(ClearingOutcomesScheduler.class);

      assertThatCode(scheduler::processOutcomes).doesNotThrowAnyException();
      // the next round still works
      port.behaviour = call -> 0;
      assertThatCode(scheduler::processOutcomes).doesNotThrowAnyException();

      assertThat(port.calls.get()).isEqualTo(2);
    });

    assertThat(output.getAll()).contains("IllegalStateException").doesNotContain(CAUSE_MARKER);
  }

  @Test
  void aRoundThatAppliedOutcomesLogsTheCountAndAnEmptyRoundLogsNothing(CapturedOutput output) {
    enabled().run(context -> {
      FakeProcessPort port = context.getBean(FakeProcessPort.class);
      ClearingOutcomesScheduler scheduler = context.getBean(ClearingOutcomesScheduler.class);

      port.behaviour = call -> 0;
      scheduler.processOutcomes();
      assertThat(output.getAll()).doesNotContain("Clearing outcomes applied");

      port.behaviour = call -> 3;
      scheduler.processOutcomes();
    });

    assertThat(output.getAll()).contains("Clearing outcomes applied: 3");
  }

  @Test
  void missingOrUnreadableDelayFailsTheStartupWhenEnabled() {
    runner.withPropertyValues("clearing.outcomes.enabled=true", "clearing.outcomes.initial-delay=1s")
      .run(context -> assertThat(context).hasFailed());
    runner.withPropertyValues("clearing.outcomes.enabled=true", "clearing.outcomes.fixed-delay=1s")
      .run(context -> assertThat(context).hasFailed());
    runner.withPropertyValues("clearing.outcomes.enabled=true", "clearing.outcomes.fixed-delay=abc",
        "clearing.outcomes.initial-delay=1s")
      .run(context -> assertThat(context).hasFailed());
    runner.withPropertyValues("clearing.outcomes.enabled=true", "clearing.outcomes.fixed-delay=1s",
        "clearing.outcomes.initial-delay=abc")
      .run(context -> assertThat(context).hasFailed());
  }
}
