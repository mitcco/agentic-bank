package com.bank.notification.kafka.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

import java.util.List;

class DiscardingRecordRecovererTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String SOURCE = "IT60X0542811101000000000001";
  private static final List<String> IBAN_FRAGMENTS =
      List.of(SOURCE, "IT60X0542811101000000000002", "IT60X0542811101000000000099", "0542811101");
  private static final String VALUE_WITH_IBAN = "{\"sourceIban\":\"" + SOURCE + "\"}";

  private final RecordingLogger logger = new RecordingLogger();
  private final DiscardingRecordRecoverer recoverer = new DiscardingRecordRecoverer(logger);

  @Test
  void logsOneErrorWithTheTransferIdAndTheClassOfTheCauseOnly() {
    ConsumerRecord<String, String> record = record(TRANSFER_ID);
    Exception failure = new ListenerExecutionFailedException("listener failed",
        new IllegalStateException("cannot reach host for " + SOURCE));

    recoverer.accept(record, failure);

    assertThat(logger.events(Level.ERROR)).hasSize(1);
    RecordingLogger.Event event = logger.events(Level.ERROR).get(0);
    assertThat(event.message())
        .contains("discarded after 3 attempts")
        .contains("transferId=" + TRANSFER_ID)
        .contains("IllegalStateException");
    assertThat(event.throwable()).isNull();
    assertThat(event.arguments()).noneMatch(argument -> argument instanceof Throwable);
    assertThat(logger.events()).hasSize(1);
    assertNoIban();
  }

  @Test
  void usesUnknownWhenTheKeyIsNull() {
    recoverer.accept(record(null),
        new ListenerExecutionFailedException("listener failed", new IllegalStateException("x")));

    assertThat(logger.events(Level.ERROR)).hasSize(1);
    assertThat(logger.events(Level.ERROR).get(0).message()).contains("transferId=unknown");
    assertNoIban();
  }

  @Test
  void usesUnknownWhenTheKeyIsAnIban() {
    recoverer.accept(record(SOURCE),
        new ListenerExecutionFailedException("listener failed", new IllegalStateException("x")));

    assertThat(logger.events(Level.ERROR)).hasSize(1);
    assertThat(logger.events(Level.ERROR).get(0).message()).contains("transferId=unknown");
    assertNoIban();
  }

  @Test
  void usesUnknownWhenTheKeyHasLineBreaks() {
    recoverer.accept(record("first\r\nforged"),
        new ListenerExecutionFailedException("listener failed", new IllegalStateException("x")));

    String message = logger.events(Level.ERROR).get(0).message();
    assertThat(message).contains("transferId=unknown").doesNotContain("forged");
  }

  @Test
  void neverThrowsWhateverTheFailureLooksLike() {
    assertThatCode(() -> {
      recoverer.accept(record(TRANSFER_ID), new RuntimeException("plain failure " + SOURCE));
      recoverer.accept(record(TRANSFER_ID), new ListenerExecutionFailedException("no cause"));
      recoverer.accept(new ConsumerRecord<>("test-topic", 0, 0L, null, null),
          new ListenerExecutionFailedException("listener failed", new RuntimeException()));
    }).doesNotThrowAnyException();

    assertThat(logger.events(Level.ERROR)).hasSize(3);
    assertNoIban();
  }

  @Test
  void rejectsANullLogger() {
    assertThatNullPointerException().isThrownBy(() -> new DiscardingRecordRecoverer(null));
  }

  @Test
  void hasAPublicConstructorWithoutArguments() {
    assertThatCode(DiscardingRecordRecoverer::new).doesNotThrowAnyException();
  }

  private static ConsumerRecord<String, String> record(String key) {
    return new ConsumerRecord<>("test-topic", 0, 0L, key, VALUE_WITH_IBAN);
  }

  private void assertNoIban() {
    for (String text : logger.texts()) {
      for (String fragment : IBAN_FRAGMENTS) {
        assertThat(text).doesNotContain(fragment);
      }
    }
  }
}
