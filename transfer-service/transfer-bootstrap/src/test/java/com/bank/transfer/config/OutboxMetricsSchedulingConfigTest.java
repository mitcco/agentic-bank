package com.bank.transfer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.bank.transfer.application.port.in.RefreshOutboxMetricsInPort;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
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
 * Lo scheduler del gauge dell'outbox (piano transfer-14, D4): acceso solo con
 * transfer.metrics.outbox.enabled=true, non lancia mai eccezioni, nel log scrive solo il nome semplice della classe
 * dell'eccezione. Stesso stile di ClearingOutcomesSchedulingConfigTest.
 */
@ExtendWith(OutputCaptureExtension.class)
class OutboxMetricsSchedulingConfigTest {

  private static final String CAUSE_MARKER = "SENSITIVE-CAUSE-MESSAGE";

  /** The in port, faked by hand: counts the calls, what each call does is scripted by the test. */
  static final class FakeRefreshPort implements RefreshOutboxMetricsInPort {
    final AtomicInteger calls = new AtomicInteger();
    volatile IntConsumer behaviour = call -> { };

    @Override
    public void refreshPendingNotifications() {
      behaviour.accept(calls.incrementAndGet());
    }
  }

  @Configuration
  static class CollaboratorsConfig {

    @Bean
    FakeRefreshPort fakeRefreshPort() {
      return new FakeRefreshPort();
    }
  }

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
    // as in the real application: an unresolved ${...} must fail, not stay as a literal string
    .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
    .withUserConfiguration(CollaboratorsConfig.class, OutboxMetricsSchedulingConfig.class)
    // Boot's conversion service is what turns "1s" into a Duration
    .withInitializer(context -> context.getBeanFactory()
      .setConversionService(ApplicationConversionService.getSharedInstance()));

  private ApplicationContextRunner enabled() {
    return runner.withPropertyValues(
      "transfer.metrics.outbox.enabled=true",
      "transfer.metrics.outbox.fixed-delay=1h",
      "transfer.metrics.outbox.initial-delay=1h");
  }

  @Test
  void noSchedulerWithoutTheProperty() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBeansOfType(OutboxMetricsScheduler.class)).isEmpty();
      assertThat(context.getBean(FakeRefreshPort.class).calls.get()).isZero();
    });
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "no", "false"})
  void anyValueOtherThanTrueLeavesItOff(String value) {
    runner.withPropertyValues("transfer.metrics.outbox.enabled=" + value)
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context.getBeansOfType(OutboxMetricsScheduler.class)).isEmpty();
      });
  }

  @Test
  void enabledPropertyCreatesExactlyOneScheduler() {
    enabled().run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBeansOfType(OutboxMetricsScheduler.class)).hasSize(1);
    });
  }

  @Test
  void enabledSchedulerCallsThePortRepeatedly() {
    runner.withPropertyValues("transfer.metrics.outbox.enabled=true", "transfer.metrics.outbox.fixed-delay=50ms",
        "transfer.metrics.outbox.initial-delay=0")
      .run(context -> {
        assertThat(context).hasNotFailed();
        FakeRefreshPort port = context.getBean(FakeRefreshPort.class);

        await().atMost(5, TimeUnit.SECONDS).until(() -> port.calls.get() >= 2);
      });
  }

  @Test
  void aRoundCallsThePortInOnce() {
    enabled().run(context -> {
      FakeRefreshPort port = context.getBean(FakeRefreshPort.class);

      context.getBean(OutboxMetricsScheduler.class).refreshPendingNotifications();

      assertThat(port.calls.get()).isEqualTo(1);
    });
  }

  @Test
  void aFailingRoundDoesNotEscapeAndOnlyTheClassNameIsLogged(CapturedOutput output) {
    enabled().run(context -> {
      FakeRefreshPort port = context.getBean(FakeRefreshPort.class);
      port.behaviour = call -> {
        throw new IllegalStateException(CAUSE_MARKER);
      };
      OutboxMetricsScheduler scheduler = context.getBean(OutboxMetricsScheduler.class);

      assertThatCode(scheduler::refreshPendingNotifications).doesNotThrowAnyException();
      // the next round still works
      port.behaviour = call -> { };
      assertThatCode(scheduler::refreshPendingNotifications).doesNotThrowAnyException();

      assertThat(port.calls.get()).isEqualTo(2);
    });

    assertThat(output.getAll()).contains("IllegalStateException").doesNotContain(CAUSE_MARKER);
  }

  @Test
  void aFailingRoundIsLoggedAsAWarn(CapturedOutput output) {
    enabled().run(context -> {
      context.getBean(FakeRefreshPort.class).behaviour = call -> {
        throw new IllegalStateException(CAUSE_MARKER);
      };

      context.getBean(OutboxMetricsScheduler.class).refreshPendingNotifications();
    });

    assertThat(output.getAll()).contains("WARN").contains("IllegalStateException");
  }

  @Test
  void missingOrUnreadableDelayFailsTheStartupWhenEnabled() {
    runner.withPropertyValues("transfer.metrics.outbox.enabled=true", "transfer.metrics.outbox.initial-delay=1s")
      .run(context -> assertThat(context).hasFailed());
    runner.withPropertyValues("transfer.metrics.outbox.enabled=true", "transfer.metrics.outbox.fixed-delay=1s")
      .run(context -> assertThat(context).hasFailed());
    runner.withPropertyValues("transfer.metrics.outbox.enabled=true", "transfer.metrics.outbox.fixed-delay=abc",
        "transfer.metrics.outbox.initial-delay=1s")
      .run(context -> assertThat(context).hasFailed());
    runner.withPropertyValues("transfer.metrics.outbox.enabled=true", "transfer.metrics.outbox.fixed-delay=1s",
        "transfer.metrics.outbox.initial-delay=abc")
      .run(context -> assertThat(context).hasFailed());
  }
}
