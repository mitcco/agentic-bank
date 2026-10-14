package com.bank.notification.kafka.port.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.bank.notification.application.command.NotifyTransferCommand;
import com.bank.notification.domain.exception.InvalidNotificationException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.event.Level;
import org.springframework.kafka.annotation.KafkaListener;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Unit tests of the Kafka listener with a hand-written use case and a recording logger. The
 * after-each check applies the scenario 10 of the contract to every test: no IBAN in any
 * recorded event.
 */
class TransferNotificationKafkaListenerTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String SOURCE = "IT60X0542811101000000000001";
  private static final String DESTINATION = "IT60X0542811101000000000002";
  private static final String REJECTED_SOURCE = "IT60X0542811101000000000099";
  private static final List<String> IBAN_FRAGMENTS =
      List.of(SOURCE, DESTINATION, REJECTED_SOURCE, "0542811101");
  private static final Instant OCCURRED_AT = Instant.parse("2026-10-13T09:30:00Z");

  private final FakeNotifyTransferInPort port = new FakeNotifyTransferInPort();
  private final RecordingLogger logger = new RecordingLogger();
  private final TransferNotificationKafkaListener listener =
      new TransferNotificationKafkaListener(port, logger);

  @AfterEach
  void noIbanInAnyRecordedEvent() {
    for (String text : logger.texts()) {
      for (String fragment : IBAN_FRAGMENTS) {
        assertThat(text).doesNotContain(fragment);
      }
    }
  }

  // Case 1 (scenario 1)
  @Test
  void passesACompletedMessageToTheUseCaseWithAllTheFields() {
    listener.handle(TRANSFER_ID, json(completedFields()));

    assertThat(port.calls()).hasSize(1);
    NotifyTransferCommand command = port.calls().get(0);
    assertThat(command.transferId()).isEqualTo(TRANSFER_ID);
    assertThat(command.sourceIban()).isEqualTo(SOURCE);
    assertThat(command.destinationIban()).isEqualTo(DESTINATION);
    assertThat(command.amount()).isEqualByComparingTo(new BigDecimal("100.25"));
    assertThat(command.currency()).isEqualTo("EUR");
    assertThat(command.status()).isEqualTo("COMPLETED");
    assertThat(command.reasons()).isEmpty();
    assertThat(command.occurredAt()).isEqualTo(OCCURRED_AT);
    assertThat(logger.events(Level.ERROR)).isEmpty();
    assertThat(logger.events(Level.WARN)).isEmpty();
  }

  // Case 2 (scenario 2)
  @Test
  void passesARejectedMessageWithTheReasonsInTheOriginalOrder() {
    listener.handle(TRANSFER_ID, json(rejectedFields()));

    assertThat(port.calls()).hasSize(1);
    NotifyTransferCommand command = port.calls().get(0);
    assertThat(command.status()).isEqualTo("REJECTED");
    assertThat(command.sourceIban()).isEqualTo(REJECTED_SOURCE);
    assertThat(command.amount()).isEqualByComparingTo(new BigDecimal("20000.00"));
    assertThat(command.reasons())
        .containsExactly("Amount must not exceed 10000 EUR", "Source IBAN is locked");
  }

  // Case 3 (scenario 3)
  @Test
  void callsTheUseCaseTwiceWithEqualCommandsWhenTheSameMessageArrivesTwice() {
    String value = json(completedFields());

    listener.handle(TRANSFER_ID, value);
    listener.handle(TRANSFER_ID, value);

    assertThat(port.calls()).hasSize(2);
    assertThat(port.calls().get(0)).isEqualTo(port.calls().get(1));
  }

  // Case 4 (scenario 4)
  @Test
  void discardsMalformedJsonWithOneErrorCarryingTheTransferIdOfTheKey() {
    assertThatCode(() -> listener.handle(TRANSFER_ID, "{not json")).doesNotThrowAnyException();

    assertThat(port.calls()).isEmpty();
    assertThat(logger.events(Level.ERROR)).hasSize(1);
    assertThat(logger.events(Level.ERROR).get(0).message())
        .contains("transferId=" + TRANSFER_ID)
        .contains("malformed JSON");
    assertThat(logger.events(Level.WARN)).isEmpty();
  }

  // Case 5 (scenario 5)
  @Test
  void discardsAMessageRejectedByTheUseCaseAndDoesNotPropagateTheException() {
    port.throwing(new InvalidNotificationException("status is not supported"));
    Map<String, String> fields = completedFields();
    fields.put("status", "\"PENDING\"");

    assertThatCode(() -> listener.handle(TRANSFER_ID, json(fields))).doesNotThrowAnyException();

    assertThat(port.calls()).hasSize(1);
    assertThat(port.calls().get(0).status()).isEqualTo("PENDING");
    assertThat(logger.events(Level.ERROR)).hasSize(1);
    assertThat(logger.events(Level.ERROR).get(0).message()).contains("transferId=" + TRANSFER_ID);
    assertThat(logger.events(Level.WARN)).isEmpty();
  }

  // Case 6 (scenario 6)
  @Test
  void mapsARejectedMessageWithoutReasonsToAnEmptyList() {
    Map<String, String> fields = rejectedFields();
    fields.remove("reasons");

    listener.handle(TRANSFER_ID, json(fields));

    assertThat(port.calls()).hasSize(1);
    assertThat(port.calls().get(0).status()).isEqualTo("REJECTED");
    assertThat(port.calls().get(0).reasons()).isEmpty();
  }

  // Case 6 (scenario 6), use case side
  @Test
  void discardsARejectedMessageWithoutReasonsWhenTheUseCaseRefusesIt() {
    port.throwing(new InvalidNotificationException("reasons must not be empty"));
    Map<String, String> fields = rejectedFields();
    fields.remove("reasons");

    assertThatCode(() -> listener.handle(TRANSFER_ID, json(fields))).doesNotThrowAnyException();

    assertThat(logger.events(Level.ERROR)).hasSize(1);
    assertThat(logger.events(Level.WARN)).isEmpty();
  }

  // Case 7 (scenario 7)
  @Test
  void mapsAZeroAmountWithoutJudgingIt() {
    Map<String, String> fields = completedFields();
    fields.put("amount", "0");

    listener.handle(TRANSFER_ID, json(fields));

    assertThat(port.calls()).hasSize(1);
    assertThat(port.calls().get(0).amount()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  // Case 7 (scenario 7), use case side
  @Test
  void discardsAZeroAmountWhenTheUseCaseRefusesIt() {
    port.throwing(new InvalidNotificationException("amount must be greater than zero"));
    Map<String, String> fields = completedFields();
    fields.put("amount", "0");

    assertThatCode(() -> listener.handle(TRANSFER_ID, json(fields))).doesNotThrowAnyException();

    assertThat(logger.events(Level.ERROR)).hasSize(1);
    assertThat(logger.events(Level.WARN)).isEmpty();
  }

  // Case 8 (scenario 8)
  @Test
  void ignoresAnUnknownFieldAndProcessesTheMessage() {
    Map<String, String> fields = completedFields();
    fields.put("extra", "\"something\"");

    listener.handle(TRANSFER_ID, json(fields));

    assertThat(port.calls()).hasSize(1);
    assertThat(logger.events(Level.ERROR)).isEmpty();
  }

  // Case 9 (scenario 9, listener side)
  @Test
  void rethrowsTheSameExceptionAndLogsOneWarnWithTheClassOnlyWhenThePortFails() {
    RuntimeException failure = new RuntimeException("database down at secret-host");
    port.throwing(failure);

    Throwable thrown = catchThrowable(() -> listener.handle(TRANSFER_ID, json(completedFields())));

    assertThat(thrown).isSameAs(failure);
    assertThat(logger.events(Level.WARN)).hasSize(1);
    String warn = logger.events(Level.WARN).get(0).message();
    assertThat(warn).contains("transferId=" + TRANSFER_ID).contains("RuntimeException");
    assertThat(warn).doesNotContain("secret-host");
    assertThat(logger.texts()).noneMatch(text -> text.contains("secret-host"));
    assertThat(logger.events(Level.ERROR)).isEmpty();
  }

  // Case 10
  @ParameterizedTest
  @MethodSource("unexpectedFailures")
  void rethrowsUnchangedAnyOtherRuntimeException(RuntimeException failure) {
    port.throwing(failure);

    Throwable thrown = catchThrowable(() -> listener.handle(TRANSFER_ID, json(completedFields())));

    assertThat(thrown).isSameAs(failure);
  }

  static Stream<RuntimeException> unexpectedFailures() {
    return Stream.of(
        new NullPointerException("npe"),
        new IllegalStateException("ise"),
        new IllegalArgumentException("iae"));
  }

  // Case 10 (Errors are not caught)
  @Test
  void doesNotCatchAnError() {
    AssertionError failure = new AssertionError("error");
    port.throwingError(failure);

    Throwable thrown = catchThrowable(() -> listener.handle(TRANSFER_ID, json(completedFields())));

    assertThat(thrown).isSameAs(failure);
    assertThat(logger.events(Level.ERROR)).isEmpty();
  }

  // Case 11
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "null"})
  void discardsAnEmptyOrNullValueWithAMessageIsEmptyError(String value) {
    assertThatCode(() -> listener.handle(TRANSFER_ID, value)).doesNotThrowAnyException();

    assertThat(port.calls()).isEmpty();
    assertThat(logger.events(Level.ERROR)).hasSize(1);
    assertThat(logger.events(Level.ERROR).get(0).message())
        .contains("message is empty")
        .contains("transferId=" + TRANSFER_ID);
  }

  // Case 11 with a null key too
  @Test
  void usesUnknownAsTransferIdForATombstoneWithoutKey() {
    assertThatCode(() -> listener.handle(null, null)).doesNotThrowAnyException();

    assertThat(port.calls()).isEmpty();
    assertThat(logger.events(Level.ERROR)).hasSize(1);
    assertThat(logger.events(Level.ERROR).get(0).message())
        .contains("transferId=unknown")
        .contains("message is empty");
  }

  // Case 12
  @ParameterizedTest
  @MethodSource("notAnObjectValues")
  void discardsAValueThatIsNotASingleJsonObject(String value) {
    assertThatCode(() -> listener.handle(TRANSFER_ID, value)).doesNotThrowAnyException();

    assertThat(port.calls()).isEmpty();
    assertThat(logger.events(Level.ERROR)).hasSize(1);
    assertThat(logger.events(Level.WARN)).isEmpty();
  }

  static Stream<String> notAnObjectValues() {
    return Stream.of(
        "[]",
        "\"abc\"",
        json(completedFields()) + " trailing",
        json(completedFields()) + "{}");
  }

  // Case 13
  @ParameterizedTest
  @MethodSource("wrongTypeFields")
  void discardsAFieldWithTheWrongJsonTypeAsInvalidFieldType(String field, String literal) {
    Map<String, String> fields = completedFields();
    fields.put(field, literal);

    assertThatCode(() -> listener.handle(TRANSFER_ID, json(fields))).doesNotThrowAnyException();

    assertThat(port.calls()).isEmpty();
    assertThat(logger.events(Level.ERROR)).hasSize(1);
    assertThat(logger.events(Level.ERROR).get(0).message()).contains("invalid field type");
  }

  static Stream<Arguments> wrongTypeFields() {
    return Stream.of(
        Arguments.of("amount", "\"100.25\""),
        Arguments.of("amount", "\"abc\""),
        Arguments.of("transferId", "123"),
        Arguments.of("reasons", "\"x\""),
        Arguments.of("reasons", "[1]"));
  }

  // Case 14
  @Test
  void discardsAnOccurredAtWrittenInAnotherFormat() {
    Map<String, String> fields = completedFields();
    fields.put("occurredAt", "\"13/10/2026\"");

    assertThatCode(() -> listener.handle(TRANSFER_ID, json(fields))).doesNotThrowAnyException();

    assertThat(port.calls()).isEmpty();
    assertThat(logger.events(Level.ERROR)).hasSize(1);
    assertThat(logger.events(Level.ERROR).get(0).message())
        .contains("occurredAt is not ISO-8601")
        .doesNotContain("13/10/2026");
  }

  // Case 14
  @Test
  void discardsANumericOccurredAt() {
    Map<String, String> fields = completedFields();
    fields.put("occurredAt", "1760347800");

    assertThatCode(() -> listener.handle(TRANSFER_ID, json(fields))).doesNotThrowAnyException();

    assertThat(port.calls()).isEmpty();
    assertThat(logger.events(Level.ERROR)).hasSize(1);
  }

  // Case 15
  @ParameterizedTest
  @ValueSource(strings = {"transferId", "sourceIban", "destinationIban", "amount", "currency",
      "status", "occurredAt"})
  void passesACommandWithTheMissingFieldNullWhenARequiredFieldIsAbsent(String field)
      throws Exception {
    Map<String, String> fields = completedFields();
    fields.remove(field);

    listener.handle(TRANSFER_ID, json(fields));

    assertThat(port.calls()).hasSize(1);
    NotifyTransferCommand command = port.calls().get(0);
    assertThat(command).isNotNull();
    Object value = NotifyTransferCommand.class.getMethod(field).invoke(command);
    assertThat(value).isNull();
  }

  // Case 16
  @Test
  void logsUnknownWhenTheTransferIdOfTheBodyIsAnIbanAndTheKeyHasLineBreaks() {
    port.throwing(new InvalidNotificationException("transferId is not valid"));
    Map<String, String> fields = completedFields();
    fields.put("transferId", "\"" + SOURCE + "\"");

    listener.handle("first\r\nsecond", json(fields));

    assertThat(logger.events(Level.ERROR)).hasSize(1);
    String message = logger.events(Level.ERROR).get(0).message();
    assertThat(message).contains("transferId=unknown").doesNotContain("\r").doesNotContain("\n");
  }

  // Case 16
  @Test
  void logsUnknownWhenTheKeyIsAnIbanAndTheBodyIsUnreadable() {
    listener.handle(SOURCE, "{not json");

    assertThat(logger.events(Level.ERROR)).hasSize(1);
    assertThat(logger.events(Level.ERROR).get(0).message()).contains("transferId=unknown");
  }

  // Case 16
  @Test
  void logsUnknownWhenTheKeyHasLineBreaksAndTheBodyIsUnreadable() {
    listener.handle("fake\r\nERROR forged line", "{not json");

    assertThat(logger.events(Level.ERROR)).hasSize(1);
    String message = logger.events(Level.ERROR).get(0).message();
    assertThat(message).contains("transferId=unknown").doesNotContain("forged");
  }

  // Case 17 (contract section 6)
  @Test
  void keepsIbansAndAmountOutOfTheErrorOfAnUnreadableJson() {
    String unreadable = "{\"transferId\":\"" + TRANSFER_ID + "\",\"sourceIban\":\"" + SOURCE
        + "\",\"destinationIban\":\"" + DESTINATION + "\",\"amount\":100.25,";

    listener.handle(TRANSFER_ID, unreadable);

    assertThat(logger.events(Level.ERROR)).hasSize(1);
    assertThat(logger.texts()).noneMatch(text -> text.contains("100.25"));
    assertThat(logger.events(Level.ERROR).get(0).throwable()).isNull();
  }

  // Case 17 (invalid type with IBANs in the document)
  @Test
  void keepsIbansAndAmountOutOfTheErrorOfAnInvalidFieldType() {
    Map<String, String> fields = completedFields();
    fields.put("amount", "\"100.25\"");

    listener.handle(TRANSFER_ID, json(fields));

    assertThat(logger.events(Level.ERROR)).hasSize(1);
    assertThat(logger.texts()).noneMatch(text -> text.contains("100.25"));
  }

  // Case 19
  @Test
  void declaresTheKafkaListenerOnTopicPropertyWithoutGroupIdNorId() throws Exception {
    Method method = TransferNotificationKafkaListener.class
        .getDeclaredMethod("onMessage", ConsumerRecord.class);

    KafkaListener annotation = method.getAnnotation(KafkaListener.class);

    assertThat(annotation).isNotNull();
    assertThat(annotation.topics()).containsExactly("${notification.topic}");
    assertThat(annotation.groupId()).isEmpty();
    assertThat(annotation.id()).isEmpty();
  }

  // Case 19
  @Test
  void hasNoSpringStereotypeOnTheClass() {
    assertThat(TransferNotificationKafkaListener.class.getAnnotations())
        .noneMatch(annotation ->
            annotation.annotationType().getName().startsWith("org.springframework"));
  }

  // Case 20
  @Test
  void rejectsANullUseCase() {
    assertThatNullPointerException()
        .isThrownBy(() -> new TransferNotificationKafkaListener(null));
    assertThatNullPointerException()
        .isThrownBy(() -> new TransferNotificationKafkaListener(null, logger));
  }

  // Case 20
  @Test
  void rejectsANullLogger() {
    assertThatNullPointerException()
        .isThrownBy(() -> new TransferNotificationKafkaListener(port, null));
  }

  // Case 21
  @Test
  void onMessageProcessesAValidRecordLikeHandle() {
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>("test-topic", 0, 0L, TRANSFER_ID, json(completedFields()));

    listener.onMessage(record);

    assertThat(port.calls()).hasSize(1);
    assertThat(port.calls().get(0).transferId()).isEqualTo(TRANSFER_ID);
  }

  // Case 21
  @Test
  void onMessageDiscardsAnInvalidRecordLikeHandle() {
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>("test-topic", 0, 0L, TRANSFER_ID, "{not json");

    assertThatCode(() -> listener.onMessage(record)).doesNotThrowAnyException();

    assertThat(port.calls()).isEmpty();
    assertThat(logger.events(Level.ERROR)).hasSize(1);
    assertThat(logger.events(Level.ERROR).get(0).message()).contains("transferId=" + TRANSFER_ID);
  }

  // Case 21
  @Test
  void onMessageDiscardsATombstoneRecordLikeHandle() {
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>("test-topic", 0, 0L, TRANSFER_ID, null);

    assertThatCode(() -> listener.onMessage(record)).doesNotThrowAnyException();

    assertThat(port.calls()).isEmpty();
    assertThat(logger.events(Level.ERROR)).hasSize(1);
    assertThat(logger.events(Level.ERROR).get(0).message()).contains("message is empty");
  }

  // Case 21
  @Test
  void onMessageRethrowsTheFailureOfThePortLikeHandle() {
    RuntimeException failure = new IllegalStateException("down");
    port.throwing(failure);
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>("test-topic", 0, 0L, TRANSFER_ID, json(completedFields()));

    Throwable thrown = catchThrowable(() -> listener.onMessage(record));

    assertThat(thrown).isSameAs(failure);
  }

  private static Map<String, String> completedFields() {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("transferId", "\"" + TRANSFER_ID + "\"");
    fields.put("sourceIban", "\"" + SOURCE + "\"");
    fields.put("destinationIban", "\"" + DESTINATION + "\"");
    fields.put("amount", "100.25");
    fields.put("currency", "\"EUR\"");
    fields.put("status", "\"COMPLETED\"");
    fields.put("occurredAt", "\"2026-10-13T09:30:00Z\"");
    return fields;
  }

  private static Map<String, String> rejectedFields() {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("transferId", "\"" + TRANSFER_ID + "\"");
    fields.put("sourceIban", "\"" + REJECTED_SOURCE + "\"");
    fields.put("destinationIban", "\"" + DESTINATION + "\"");
    fields.put("amount", "20000.00");
    fields.put("currency", "\"EUR\"");
    fields.put("status", "\"REJECTED\"");
    fields.put("reasons", "[\"Amount must not exceed 10000 EUR\", \"Source IBAN is locked\"]");
    fields.put("occurredAt", "\"2026-10-13T09:31:00Z\"");
    return fields;
  }

  private static String json(Map<String, String> fields) {
    StringBuilder builder = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, String> field : fields.entrySet()) {
      if (!first) {
        builder.append(",");
      }
      builder.append("\"").append(field.getKey()).append("\":").append(field.getValue());
      first = false;
    }
    return builder.append("}").toString();
  }
}
