package com.bank.notification.kafka.port.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.bank.notification.application.service.NotifyTransferServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Listener plus the real {@code NotifyTransferServiceImpl}, with fake outbound ports: it
 * proves the chain from the Kafka value to the store and the log.
 */
class TransferNotificationKafkaListenerPipelineTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final List<String> IBAN_FRAGMENTS = List.of(
      "IT60X0542811101000000000001", "IT60X0542811101000000000002",
      "IT60X0542811101000000000099", "0542811101");

  private static final String COMPLETED = "{\"transferId\":\"" + TRANSFER_ID + "\","
      + "\"sourceIban\":\"IT60X0542811101000000000001\","
      + "\"destinationIban\":\"IT60X0542811101000000000002\","
      + "\"amount\":100.25,\"currency\":\"EUR\",\"status\":\"COMPLETED\","
      + "\"occurredAt\":\"2026-10-13T09:30:00Z\"}";
  private static final String REJECTED = "{\"transferId\":\"" + TRANSFER_ID + "\","
      + "\"sourceIban\":\"IT60X0542811101000000000099\","
      + "\"destinationIban\":\"IT60X0542811101000000000002\","
      + "\"amount\":20000.00,\"currency\":\"EUR\",\"status\":\"REJECTED\","
      + "\"reasons\":[\"Amount must not exceed 10000 EUR\",\"Source IBAN is locked\"],"
      + "\"occurredAt\":\"2026-10-13T09:31:00Z\"}";

  private final List<String> registry = new ArrayList<>();
  private final FakeNotificationStoreOutPort store = new FakeNotificationStoreOutPort(registry);
  private final FakeNotificationLogOutPort log = new FakeNotificationLogOutPort(registry);
  private final RecordingLogger logger = new RecordingLogger();
  private final TransferNotificationKafkaListener listener =
      new TransferNotificationKafkaListener(new NotifyTransferServiceImpl(store, log), logger);

  @AfterEach
  void noIbanInAnyRecordedEvent() {
    for (String text : logger.texts()) {
      for (String fragment : IBAN_FRAGMENTS) {
        assertThat(text).doesNotContain(fragment);
      }
    }
  }

  @Test
  void savesThenLogsACompletedMessage() {
    listener.handle(TRANSFER_ID, COMPLETED);

    assertThat(registry).containsExactly("save", "log");
    assertThat(store.saved()).hasSize(1);
    assertThat(store.saved().get(0).transferId()).isEqualTo(TRANSFER_ID);
    assertThat(store.saved().get(0).amount().amount()).isEqualByComparingTo(new BigDecimal("100.25"));
    assertThat(log.logged()).hasSize(1);
    assertThat(logger.events(Level.ERROR)).isEmpty();
    assertThat(logger.events(Level.WARN)).isEmpty();
  }

  @Test
  void savesThenLogsARejectedMessageWithBothReasons() {
    listener.handle(TRANSFER_ID, REJECTED);

    assertThat(registry).containsExactly("save", "log");
    assertThat(store.saved().get(0).reasons())
        .containsExactly("Amount must not exceed 10000 EUR", "Source IBAN is locked");
    assertThat(log.logged().get(0).reasons())
        .containsExactly("Amount must not exceed 10000 EUR", "Source IBAN is locked");
  }

  @Test
  void discardsAnUnknownStatusWithoutSaving() {
    String pending = COMPLETED.replace("\"COMPLETED\"", "\"PENDING\"");

    assertThatCode(() -> listener.handle(TRANSFER_ID, pending)).doesNotThrowAnyException();

    assertThat(store.saved()).isEmpty();
    assertThat(log.logged()).isEmpty();
    assertThat(logger.events(Level.ERROR)).hasSize(1);
    assertThat(logger.events(Level.WARN)).isEmpty();
  }

  @Test
  void discardsARejectedMessageWithoutReasonsWithoutSaving() {
    String withoutReasons = REJECTED.replace(
        "\"reasons\":[\"Amount must not exceed 10000 EUR\",\"Source IBAN is locked\"],", "");

    assertThatCode(() -> listener.handle(TRANSFER_ID, withoutReasons)).doesNotThrowAnyException();

    assertThat(store.saved()).isEmpty();
    assertThat(log.logged()).isEmpty();
    assertThat(logger.events(Level.ERROR)).hasSize(1);
  }

  @Test
  void discardsAZeroAmountWithoutSaving() {
    String zero = COMPLETED.replace("\"amount\":100.25", "\"amount\":0");

    assertThatCode(() -> listener.handle(TRANSFER_ID, zero)).doesNotThrowAnyException();

    assertThat(store.saved()).isEmpty();
    assertThat(log.logged()).isEmpty();
    assertThat(logger.events(Level.ERROR)).hasSize(1);
  }

  @Test
  void rethrowsTheFailureOfTheStoreAndWritesNoLogLine() {
    RuntimeException failure = new IllegalStateException("mongo down");
    store.throwing(failure);

    Throwable thrown = catchThrowable(() -> listener.handle(TRANSFER_ID, COMPLETED));

    assertThat(thrown).isSameAs(failure);
    assertThat(registry).containsExactly("save");
    assertThat(log.logged()).isEmpty();
    assertThat(logger.events(Level.WARN)).hasSize(1);
    assertThat(logger.events(Level.ERROR)).isEmpty();
  }
}
