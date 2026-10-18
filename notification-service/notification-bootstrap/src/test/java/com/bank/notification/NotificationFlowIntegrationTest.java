package com.bank.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import com.bank.notification.kafka.port.in.kafka.TransferNotificationKafkaListener;
import com.bank.notification.mongo.repository.model.TransferNotificationDocument;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Flow test without Docker: the real listener, service, store port and log port of the context,
 * with a hand-written repository instead of MongoDB. The listener is called directly with a
 * record; the container is never started.
 */
@SpringBootTest
class NotificationFlowIntegrationTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String SOURCE = "IT60X0542811101000000000001";
  private static final String DESTINATION = "IT60X0542811101000000000002";
  private static final String REJECTED_SOURCE = "IT60X0542811101000000000099";
  private static final List<String> IBAN_FRAGMENTS =
      List.of(SOURCE, DESTINATION, REJECTED_SOURCE, "0542811101");

  private static final String COMPLETED_JSON = "{"
      + "\"transferId\": \"" + TRANSFER_ID + "\","
      + "\"sourceIban\": \"" + SOURCE + "\","
      + "\"destinationIban\": \"" + DESTINATION + "\","
      + "\"amount\": 100.25,"
      + "\"currency\": \"EUR\","
      + "\"status\": \"COMPLETED\","
      + "\"occurredAt\": \"2026-10-13T09:30:00Z\"}";

  private static final String REJECTED_JSON = "{"
      + "\"transferId\": \"" + TRANSFER_ID + "\","
      + "\"sourceIban\": \"" + REJECTED_SOURCE + "\","
      + "\"destinationIban\": \"" + DESTINATION + "\","
      + "\"amount\": 20000.00,"
      + "\"currency\": \"EUR\","
      + "\"status\": \"REJECTED\","
      + "\"reasons\": [\"Amount must not exceed 10000 EUR\", \"Source IBAN is locked\"],"
      + "\"occurredAt\": \"2026-10-13T09:31:00Z\"}";

  /** Replaces the repository of MongoDB with the hand-written fake. */
  @TestConfiguration(proxyBeanMethods = false)
  static class FakeRepositoryConfiguration {

    @Bean
    @Primary
    RecordingTransferNotificationRepository recordingTransferNotificationRepository() {
      return new RecordingTransferNotificationRepository();
    }
  }

  /** Thread-safe collector of Logback events. */
  static class CollectingAppender extends AppenderBase<ILoggingEvent> {

    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

    @Override
    protected void append(ILoggingEvent event) {
      events.add(event);
    }

    List<ILoggingEvent> events() {
      return events;
    }
  }

  @Autowired
  private TransferNotificationKafkaListener listener;

  @Autowired
  private RecordingTransferNotificationRepository repository;

  private CollectingAppender appender;
  private ch.qos.logback.classic.Logger rootLogger;

  @BeforeEach
  void setUp() {
    repository.clear();
    appender = new CollectingAppender();
    appender.start();
    rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
        org.slf4j.Logger.ROOT_LOGGER_NAME);
    rootLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(appender);
    appender.stop();
  }

  @Test
  void savesACompletedNotificationAndLogsOneLineWithoutIban() {
    listener.onMessage(record(TRANSFER_ID, COMPLETED_JSON));

    assertThat(repository.saved()).hasSize(1);
    TransferNotificationDocument document = repository.saved().get(0);
    assertThat(document.getTransferId()).isEqualTo(TRANSFER_ID);
    assertThat(document.getSourceIban()).isEqualTo(SOURCE);
    assertThat(document.getDestinationIban()).isEqualTo(DESTINATION);
    assertThat(document.getAmount().bigDecimalValue()).isEqualByComparingTo(new BigDecimal("100.25"));
    assertThat(document.getAmount().bigDecimalValue().scale()).isEqualTo(2);
    assertThat(document.getCurrency()).isEqualTo("EUR");
    assertThat(document.getStatus()).isEqualTo("COMPLETED");
    assertThat(document.getReasons()).isEmpty();
    assertThat(document.getOccurredAt()).isEqualTo(Date.from(Instant.parse("2026-10-13T09:30:00Z")));

    List<ILoggingEvent> infos = successLines();
    assertThat(infos).hasSize(1);
    assertThat(infos.get(0).getFormattedMessage())
        .contains(TRANSFER_ID, "outcome=COMPLETED", "amount=100.25", "currency=EUR");
    assertNoIbanInAnyEvent();
  }

  @Test
  void savesARejectedNotificationWithBothReasonsInOrderAndLogsThem() {
    listener.onMessage(record(TRANSFER_ID, REJECTED_JSON));

    assertThat(repository.saved()).hasSize(1);
    TransferNotificationDocument document = repository.saved().get(0);
    assertThat(document.getStatus()).isEqualTo("REJECTED");
    assertThat(document.getAmount().bigDecimalValue()).isEqualByComparingTo(new BigDecimal("20000.00"));
    assertThat(document.getReasons())
        .containsExactly("Amount must not exceed 10000 EUR", "Source IBAN is locked");

    List<ILoggingEvent> infos = successLines();
    assertThat(infos).hasSize(1);
    assertThat(infos.get(0).getFormattedMessage())
        .contains("outcome=REJECTED", "amount=20000.00")
        .contains("reasons=[\"Amount must not exceed 10000 EUR\", \"Source IBAN is locked\"]");
    assertNoIbanInAnyEvent();
  }

  @Test
  void savesTheSameRecordTwiceWithTheSameIdAndLogsTwoLines() {
    listener.onMessage(record(TRANSFER_ID, COMPLETED_JSON));
    listener.onMessage(record(TRANSFER_ID, COMPLETED_JSON));

    assertThat(repository.saved()).hasSize(2);
    assertThat(repository.saved()).extracting(TransferNotificationDocument::getTransferId)
        .containsExactly(TRANSFER_ID, TRANSFER_ID);
    assertThat(successLines()).hasSize(2);
    assertNoIbanInAnyEvent();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{not json",
      "{\"transferId\":\"3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93\","
          + "\"sourceIban\":\"IT60X0542811101000000000001\","
          + "\"destinationIban\":\"IT60X0542811101000000000002\",\"amount\":100.25,"
          + "\"currency\":\"EUR\",\"status\":\"PENDING\",\"occurredAt\":\"2026-10-13T09:30:00Z\"}",
      "{\"transferId\":\"3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93\","
          + "\"sourceIban\":\"IT60X0542811101000000000099\","
          + "\"destinationIban\":\"IT60X0542811101000000000002\",\"amount\":20000.00,"
          + "\"currency\":\"EUR\",\"status\":\"REJECTED\",\"occurredAt\":\"2026-10-13T09:31:00Z\"}",
      "{\"transferId\":\"3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93\","
          + "\"sourceIban\":\"IT60X0542811101000000000001\","
          + "\"destinationIban\":\"IT60X0542811101000000000002\",\"amount\":0,"
          + "\"currency\":\"EUR\",\"status\":\"COMPLETED\",\"occurredAt\":\"2026-10-13T09:30:00Z\"}",
      ""})
  void discardsAnInvalidMessageWithOneErrorLineAndNoSave(String json) {
    assertThatCode(() -> listener.onMessage(record(TRANSFER_ID, json))).doesNotThrowAnyException();

    assertThat(repository.saved()).isEmpty();
    assertThat(successLines()).isEmpty();
    List<ILoggingEvent> errors = appender.events().stream()
        .filter(event -> event.getLevel() == Level.ERROR).toList();
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0).getFormattedMessage()).contains("discarded");
    assertNoIbanInAnyEvent();
  }

  @Test
  void ignoresAnUnknownFieldAndProcessesTheMessage() {
    String json = COMPLETED_JSON.substring(0, COMPLETED_JSON.length() - 1)
        + ",\"unknownField\": \"whatever\"}";

    listener.onMessage(record(TRANSFER_ID, json));

    assertThat(repository.saved()).hasSize(1);
    assertNoIbanInAnyEvent();
  }

  @Test
  void rethrowsTheSameExceptionWhenTheSaveFailsAndWritesNoSuccessLine() {
    IllegalStateException failure = new IllegalStateException("simulated database failure");
    repository.failWith(failure);

    assertThatThrownBy(() -> listener.onMessage(record(TRANSFER_ID, COMPLETED_JSON)))
        .isSameAs(failure);

    assertThat(successLines()).isEmpty();
    List<ILoggingEvent> warnings = appender.events().stream()
        .filter(event -> event.getLevel() == Level.WARN).toList();
    assertThat(warnings).hasSize(1);
    assertThat(warnings.get(0).getFormattedMessage())
        .contains("java.lang.IllegalStateException")
        .doesNotContain("simulated database failure");
    assertNoIbanInAnyEvent();
  }

  private static ConsumerRecord<String, String> record(String key, String json) {
    return new ConsumerRecord<>("test-topic", 0, 0L, key, json);
  }

  /** The INFO lines written by the log port after a successful save. */
  private List<ILoggingEvent> successLines() {
    return appender.events().stream()
        .filter(event -> event.getLevel() == Level.INFO)
        .filter(event -> event.getFormattedMessage().startsWith("Transfer notification transferId="))
        .toList();
  }

  private void assertNoIbanInAnyEvent() {
    for (ILoggingEvent event : appender.events()) {
      assertNoIban(event.getFormattedMessage());
      IThrowableProxy throwable = event.getThrowableProxy();
      while (throwable != null) {
        assertNoIban(throwable.getClassName() + ": " + throwable.getMessage());
        throwable = throwable.getCause();
      }
    }
  }

  private static void assertNoIban(String text) {
    for (String fragment : IBAN_FRAGMENTS) {
      assertThat(text).doesNotContain(fragment);
    }
  }
}
