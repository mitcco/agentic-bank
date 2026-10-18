package com.bank.transfer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.bank.transfer.application.port.in.ReconcileExternalTransfersInPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The scheduler of the reconciliation: off unless clearing.outcomes.enabled=true (the same switch of the outcomes
 * scheduler), threshold = clock - stale-after, never throws, logs only the simple class name of an exception,
 * invalid settings fail the startup. Same style as ClearingOutcomesSchedulingConfigTest.
 */
@ExtendWith(OutputCaptureExtension.class)
class ClearingReconciliationSchedulingConfigTest {

  private static final String CAUSE_MARKER = "SENSITIVE-CAUSE-MESSAGE";
  private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  /** One call received by the port in. */
  record Call(Instant createdBefore, int maxTransfers) {
  }

  /** The in port, faked by hand: records the calls, the result of each call is scripted by the test. */
  static final class FakeReconcilePort implements ReconcileExternalTransfersInPort {
    final List<Call> calls = new CopyOnWriteArrayList<>();
    final AtomicInteger count = new AtomicInteger();
    volatile IntUnaryOperator behaviour = call -> 0;

    @Override
    public int reconcileStale(Instant createdBefore, int maxTransfers) {
      calls.add(new Call(createdBefore, maxTransfers));
      return behaviour.applyAsInt(count.incrementAndGet());
    }
  }

  @Configuration
  static class CollaboratorsConfig {

    @Bean
    FakeReconcilePort fakeReconcilePort() {
      return new FakeReconcilePort();
    }

    @Bean
    Clock clock() {
      return FIXED_CLOCK;
    }
  }

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
    // as in the real application: an unresolved ${...} must fail, not stay as a literal string
    .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
    .withUserConfiguration(CollaboratorsConfig.class, ClearingReconciliationSchedulingConfig.class)
    // Boot's conversion service is what turns "5m" into a Duration
    .withInitializer(context -> context.getBeanFactory()
      .setConversionService(ApplicationConversionService.getSharedInstance()));

  private ApplicationContextRunner enabled() {
    return runner.withPropertyValues(
      "clearing.outcomes.enabled=true",
      "clearing.reconcile.stale-after=5m",
      "clearing.reconcile.batch-size=7",
      "clearing.reconcile.fixed-delay=1h",
      "clearing.reconcile.initial-delay=1h");
  }

  /** All the settings valid, except the ones given afterwards (a later value of the same key wins). */
  private ApplicationContextRunner enabledWith(String... overrides) {
    return enabled().withPropertyValues(overrides);
  }

  // ------------------------------------------------------------------ on and off

  @Test
  void noSchedulerWithoutTheOutcomesSwitch() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBeansOfType(ClearingReconciliationScheduler.class)).isEmpty();
      assertThat(context.getBean(FakeReconcilePort.class).calls).isEmpty();
    });
  }

  @Test
  void noSchedulerWhenTheSwitchIsFalseEvenWithTheSettings() {
    runner.withPropertyValues("clearing.outcomes.enabled=false", "clearing.reconcile.stale-after=5m",
        "clearing.reconcile.batch-size=7", "clearing.reconcile.fixed-delay=1h",
        "clearing.reconcile.initial-delay=1h")
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context.getBeansOfType(ClearingReconciliationScheduler.class)).isEmpty();
      });
  }

  @Test
  void enabledWritesTheSchedulerWithTheSettings() {
    enabled().run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBeansOfType(ClearingReconciliationScheduler.class)).hasSize(1);
      FakeReconcilePort port = context.getBean(FakeReconcilePort.class);
      ClearingReconciliationScheduler scheduler = context.getBean(ClearingReconciliationScheduler.class);

      scheduler.reconcile();

      assertThat(port.calls).containsExactly(new Call(NOW.minus(Duration.ofMinutes(5)), 7));
    });
  }

  @Test
  void theThresholdFollowsTheStaleAfterSetting() {
    enabledWith("clearing.reconcile.stale-after=90s", "clearing.reconcile.batch-size=1").run(context -> {
      FakeReconcilePort port = context.getBean(FakeReconcilePort.class);

      context.getBean(ClearingReconciliationScheduler.class).reconcile();

      assertThat(port.calls).containsExactly(new Call(NOW.minusSeconds(90), 1));
    });
  }

  @Test
  void schedulerRunsRepeatedlyWhenEnabled() {
    enabledWith("clearing.reconcile.fixed-delay=50ms", "clearing.reconcile.initial-delay=0")
      .run(context -> {
        assertThat(context).hasNotFailed();
        FakeReconcilePort port = context.getBean(FakeReconcilePort.class);

        await().atMost(5, TimeUnit.SECONDS).until(() -> port.calls.size() >= 2);
      });
  }

  // ------------------------------------------------------------------ failures and logs

  @Test
  void aFailingRoundDoesNotEscapeAndOnlyTheClassNameIsLogged(CapturedOutput output) {
    enabled().run(context -> {
      FakeReconcilePort port = context.getBean(FakeReconcilePort.class);
      port.behaviour = call -> {
        throw new IllegalStateException(CAUSE_MARKER);
      };
      ClearingReconciliationScheduler scheduler = context.getBean(ClearingReconciliationScheduler.class);

      assertThatCode(scheduler::reconcile).doesNotThrowAnyException();
      // the next round still works
      port.behaviour = call -> 0;
      assertThatCode(scheduler::reconcile).doesNotThrowAnyException();

      assertThat(port.calls).hasSize(2);
    });

    assertThat(output.getAll()).contains("IllegalStateException").doesNotContain(CAUSE_MARKER);
  }

  @Test
  void aRoundThatReconciledTransfersLogsTheCountAndAnEmptyRoundLogsNothing(CapturedOutput output) {
    enabled().run(context -> {
      FakeReconcilePort port = context.getBean(FakeReconcilePort.class);
      ClearingReconciliationScheduler scheduler = context.getBean(ClearingReconciliationScheduler.class);

      port.behaviour = call -> 0;
      scheduler.reconcile();
      assertThat(output.getAll()).doesNotContain("Clearing reconciliation applied");

      port.behaviour = call -> 3;
      scheduler.reconcile();
    });

    assertThat(output.getAll()).contains("Clearing reconciliation applied: 3");
  }

  // ------------------------------------------------------------------ invalid settings

  @Test
  void invalidSettingsFailTheStartup() {
    for (String[] invalid : new String[][] {
      {"clearing.reconcile.batch-size=0"},
      {"clearing.reconcile.batch-size=-1"},
      {"clearing.reconcile.batch-size=abc"},
      {"clearing.reconcile.stale-after=0s"},
      {"clearing.reconcile.stale-after=-5m"},
      {"clearing.reconcile.stale-after=abc"},
      {"clearing.reconcile.fixed-delay=abc"},
      {"clearing.reconcile.initial-delay=abc"}}) {
      enabledWith(invalid).run(context -> assertThat(context).as(String.join(",", invalid)).hasFailed());
    }
  }

  @Test
  void missingSettingsFailTheStartupWhenEnabled() {
    String[] all = {"clearing.reconcile.stale-after=5m", "clearing.reconcile.batch-size=7",
      "clearing.reconcile.fixed-delay=1h", "clearing.reconcile.initial-delay=1h"};
    for (int missing = 0; missing < all.length; missing++) {
      String[] others = new String[all.length - 1];
      for (int i = 0, j = 0; i < all.length; i++) {
        if (i != missing) {
          others[j++] = all[i];
        }
      }
      String absent = all[missing];
      runner.withPropertyValues("clearing.outcomes.enabled=true")
        .withPropertyValues(others)
        .run(context -> assertThat(context).as("without " + absent).hasFailed());
    }
  }

  // ------------------------------------------------------------------ the scheduler itself

  @Test
  void constructorRefusesNullsAndInvalidValues() {
    FakeReconcilePort port = new FakeReconcilePort();
    Duration fiveMinutes = Duration.ofMinutes(5);

    assertThatThrownBy(() -> new ClearingReconciliationScheduler(null, FIXED_CLOCK, fiveMinutes, 1))
      .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClearingReconciliationScheduler(port, null, fiveMinutes, 1))
      .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClearingReconciliationScheduler(port, FIXED_CLOCK, null, 1))
      .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClearingReconciliationScheduler(port, FIXED_CLOCK, Duration.ZERO, 1))
      .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ClearingReconciliationScheduler(port, FIXED_CLOCK, Duration.ofMinutes(-1), 1))
      .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ClearingReconciliationScheduler(port, FIXED_CLOCK, fiveMinutes, 0))
      .isInstanceOf(IllegalArgumentException.class);
    assertThat(port.calls).isEmpty();
  }

  @Test
  void anAcceptableConstructionDoesNotCallThePort() {
    FakeReconcilePort port = new FakeReconcilePort();

    new ClearingReconciliationScheduler(port, FIXED_CLOCK, Duration.ofMinutes(5), 1);

    assertThat(port.calls).isEmpty();
  }
}
