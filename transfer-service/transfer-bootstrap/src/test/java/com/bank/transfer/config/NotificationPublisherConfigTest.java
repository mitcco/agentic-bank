package com.bank.transfer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.bank.transfer.application.dto.PublishFailureStageEnum;
import com.bank.transfer.application.dto.PublishResultDto;
import com.bank.transfer.application.exception.InvalidOutboxRowException;
import com.bank.transfer.application.port.in.PublishPendingNotificationsInPort;
import com.bank.transfer.application.port.out.NotificationSenderOutPort;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;

/**
 * The wiring of the outbox publisher: off unless notification.publisher.enabled=true (with a WARN), the sender bean
 * always present, startup failures on unreadable settings. Same style as FraudConfigTest.
 */
@ExtendWith(OutputCaptureExtension.class)
class NotificationPublisherConfigTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String CAUSE_MARKER = "SENSITIVE-CAUSE-MESSAGE";
  private static final String DISABLED_WARN =
    "Outbox publisher DISABLED (notification.publisher.enabled=false): notifications stay in table outbox";

  /** Calls received by the fake in port; the behaviour of each call is scripted by the test. */
  static final class FakePublishPort implements PublishPendingNotificationsInPort {
    final List<Integer> calls = new CopyOnWriteArrayList<>();
    volatile IntFunction<PublishResultDto> behaviour = call -> new PublishResultDto(0, 0, null, null);

    @Override
    public PublishResultDto publishPending(int maxRows) {
      calls.add(maxRows);
      return behaviour.apply(calls.size());
    }
  }

  @Configuration
  static class CollaboratorsConfig {

    @Bean
    FakePublishPort fakePublishPort() {
      return new FakePublishPort();
    }

    @Bean
    @SuppressWarnings("unchecked")
    KafkaOperations<String, String> kafkaOperations() {
      return Mockito.mock(KafkaOperations.class);
    }
  }

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
    // as in the real application: an unresolved ${...} must fail, not stay as a literal string
    .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
    .withUserConfiguration(CollaboratorsConfig.class, NotificationPublisherConfig.class,
      NotificationPublisherSchedulingConfig.class)
    // Boot's conversion service is what turns "1s" into a Duration for @Value
    .withInitializer(context -> context.getBeanFactory()
      .setConversionService(ApplicationConversionService.getSharedInstance()));

  private ApplicationContextRunner enabled() {
    return runner.withPropertyValues(
      "notification.topic=t.test",
      "notification.publisher.send-timeout=1s",
      "notification.publisher.enabled=true",
      "notification.publisher.fixed-delay=1h",
      "notification.publisher.initial-delay=1h",
      "notification.publisher.batch-size=7");
  }

  // C1
  @Test
  void withoutTheEnabledPropertyThereIsNoSchedulerButTheSenderExistsAndTheLogWarns(CapturedOutput output) {
    runner.withPropertyValues("notification.topic=t.test", "notification.publisher.send-timeout=1s")
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context.getBeansOfType(NotificationPublisherScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(NotificationSenderOutPort.class)).hasSize(1);
      });

    assertThat(output.getAll()).contains(DISABLED_WARN);
  }

  // C2
  @Test
  void withEnabledFalseThereIsNoSchedulerButTheSenderExistsAndTheLogWarns(CapturedOutput output) {
    runner.withPropertyValues("notification.topic=t.test", "notification.publisher.send-timeout=1s",
        "notification.publisher.enabled=false")
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context.getBeansOfType(NotificationPublisherScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(NotificationSenderOutPort.class)).hasSize(1);
        // nobody calls the port in
        assertThat(context.getBean(FakePublishPort.class).calls).isEmpty();
      });

    assertThat(output.getAll()).contains(DISABLED_WARN);
  }

  // C2 (formats written in a different way: only "true", in any letter case, turns it on; see the TRUE/True test below)
  @ParameterizedTest
  @ValueSource(strings = {"", "no", "0", "FALSE"})
  void anyValueOtherThanTrueLeavesThePublisherOff(String value) {
    runner.withPropertyValues("notification.topic=t.test", "notification.publisher.send-timeout=1s",
        "notification.publisher.enabled=" + value)
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context.getBeansOfType(NotificationPublisherScheduler.class)).isEmpty();
      });
  }

  // C2/C3 (consistency: the scheduler condition ignores case, so the log must not claim DISABLED)
  @ParameterizedTest
  @ValueSource(strings = {"TRUE", "True"})
  void enabledWrittenWithDifferentCaseStartsTheSchedulerAndDoesNotLogDisabled(String value, CapturedOutput output) {
    runner.withPropertyValues("notification.topic=t.test", "notification.publisher.send-timeout=1s",
        "notification.publisher.enabled=" + value, "notification.publisher.fixed-delay=1h",
        "notification.publisher.initial-delay=1h", "notification.publisher.batch-size=7")
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context.getBeansOfType(NotificationPublisherScheduler.class)).hasSize(1);
        assertThat(context.getBeansOfType(NotificationSenderOutPort.class)).hasSize(1);
      });

    assertThat(output.getAll()).doesNotContain(DISABLED_WARN);
  }

  // C3
  @Test
  void whenEnabledTheSchedulerCallsTheInPortRepeatedlyWithTheConfiguredBatchSize(CapturedOutput output) {
    runner.withPropertyValues("notification.topic=t.test", "notification.publisher.send-timeout=1s",
        "notification.publisher.enabled=true", "notification.publisher.fixed-delay=50ms",
        "notification.publisher.initial-delay=0", "notification.publisher.batch-size=7")
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context.getBeansOfType(NotificationPublisherScheduler.class)).hasSize(1);
        assertThat(context.getBeansOfType(NotificationSenderOutPort.class)).hasSize(1);
        FakePublishPort port = context.getBean(FakePublishPort.class);

        await().atMost(5, TimeUnit.SECONDS).until(() -> port.calls.size() >= 2);

        assertThat(port.calls).allMatch(maxRows -> maxRows == 7);
      });

    assertThat(output.getAll()).doesNotContain(DISABLED_WARN);
  }

  // C4
  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "abc", ""})
  void anInvalidBatchSizeFailsTheStartupWhenEnabled(String batchSize) {
    runner.withPropertyValues("notification.topic=t.test", "notification.publisher.send-timeout=1s",
        "notification.publisher.enabled=true", "notification.publisher.fixed-delay=1s",
        "notification.publisher.initial-delay=1s", "notification.publisher.batch-size=" + batchSize)
      .run(context -> assertThat(context).hasFailed());
  }

  // C4
  @Test
  void aMissingBatchSizeFailsTheStartupWhenEnabled() {
    runner.withPropertyValues("notification.topic=t.test", "notification.publisher.send-timeout=1s",
        "notification.publisher.enabled=true", "notification.publisher.fixed-delay=1s",
        "notification.publisher.initial-delay=1s")
      .run(context -> assertThat(context).hasFailed());
  }

  // C4 (a missing or unreadable duration)
  @Test
  void aMissingOrUnreadableDelayFailsTheStartupWhenEnabled() {
    runner.withPropertyValues("notification.topic=t.test", "notification.publisher.send-timeout=1s",
        "notification.publisher.enabled=true", "notification.publisher.initial-delay=1s",
        "notification.publisher.batch-size=7")
      .run(context -> assertThat(context).hasFailed());
    runner.withPropertyValues("notification.topic=t.test", "notification.publisher.send-timeout=1s",
        "notification.publisher.enabled=true", "notification.publisher.fixed-delay=abc",
        "notification.publisher.initial-delay=1s", "notification.publisher.batch-size=7")
      .run(context -> assertThat(context).hasFailed());
  }

  // C5
  @Test
  void aMissingSendTimeoutFailsTheStartup() {
    runner.withPropertyValues("notification.topic=t.test")
      .run(context -> assertThat(context).hasFailed());
  }

  // C5
  @ParameterizedTest
  @ValueSource(strings = {"abc", "0s", "-1s"})
  void anUnreadableOrNonPositiveSendTimeoutFailsTheStartup(String timeout) {
    runner.withPropertyValues("notification.topic=t.test", "notification.publisher.send-timeout=" + timeout)
      .run(context -> assertThat(context).hasFailed());
  }

  // C5 (the topic comes from notification.topic: without it there is no sender)
  @Test
  void aMissingTopicFailsTheStartup() {
    runner.withPropertyValues("notification.publisher.send-timeout=1s")
      .run(context -> assertThat(context).hasFailed());
  }

  // C6
  @Test
  void aRuntimeExceptionOfTheInPortDoesNotEscapeTheSchedulerAndOnlyTheClassNameIsLogged(CapturedOutput output) {
    enabled().run(context -> {
      FakePublishPort port = context.getBean(FakePublishPort.class);
      port.behaviour = call -> {
        throw new IllegalStateException(CAUSE_MARKER);
      };
      NotificationPublisherScheduler scheduler = context.getBean(NotificationPublisherScheduler.class);

      assertThatCode(scheduler::publish).doesNotThrowAnyException();
      // the next round still works
      port.behaviour = call -> new PublishResultDto(0, 0, null, null);
      assertThatCode(scheduler::publish).doesNotThrowAnyException();

      assertThat(port.calls).containsExactly(7, 7);
    });

    assertThat(output.getAll()).contains("IllegalStateException").doesNotContain(CAUSE_MARKER);
  }

  // C6
  @Test
  void aFailedResultIsLoggedWithTheTransferIdAndTheStageAndTheSchedulerKeepsGoing(CapturedOutput output) {
    enabled().run(context -> {
      FakePublishPort port = context.getBean(FakePublishPort.class);
      port.behaviour = call -> new PublishResultDto(3, 1, TRANSFER_ID, PublishFailureStageEnum.SEND);
      NotificationPublisherScheduler scheduler = context.getBean(NotificationPublisherScheduler.class);

      assertThatCode(scheduler::publish).doesNotThrowAnyException();
      assertThatCode(scheduler::publish).doesNotThrowAnyException();

      assertThat(port.calls).hasSize(2);
    });

    assertThat(output.getAll()).contains(TRANSFER_ID).contains("SEND");
  }

  // invalid outbox row (correction 4)
  @Test
  void anInvalidOutboxRowIsLoggedWithItsIdOnlyAndTheNextRoundStillRuns(CapturedOutput output) {
    enabled().run(context -> {
      FakePublishPort port = context.getBean(FakePublishPort.class);
      port.behaviour = call -> {
        throw new InvalidOutboxRowException(42);
      };
      NotificationPublisherScheduler scheduler = context.getBean(NotificationPublisherScheduler.class);

      assertThatCode(scheduler::publish).doesNotThrowAnyException();
      // the next round is executed
      port.behaviour = call -> new PublishResultDto(0, 0, null, null);
      assertThatCode(scheduler::publish).doesNotThrowAnyException();

      assertThat(port.calls).containsExactly(7, 7);
    });

    // handled by its own catch (a WARN with the id), not by the generic one that logs the class name
    assertThat(output.getAll()).contains("Outbox row 42 is invalid")
      .doesNotContain("InvalidOutboxRowException").doesNotContain("Invalid outbox row");
  }
}
