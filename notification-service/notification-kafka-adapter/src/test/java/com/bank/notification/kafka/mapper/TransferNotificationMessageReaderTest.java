package com.bank.notification.kafka.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.notification.kafka.dto.TransferNotificationMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

class TransferNotificationMessageReaderTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String SOURCE = "IT60X0542811101000000000001";
  private static final String DESTINATION = "IT60X0542811101000000000002";

  private static final String COMPLETED = "{\"transferId\":\"" + TRANSFER_ID + "\","
      + "\"sourceIban\":\"" + SOURCE + "\",\"destinationIban\":\"" + DESTINATION + "\","
      + "\"amount\":100.25,\"currency\":\"EUR\",\"status\":\"COMPLETED\","
      + "\"occurredAt\":\"2026-10-13T09:30:00Z\"}";

  private final TransferNotificationMessageReader reader = new TransferNotificationMessageReader();

  @Test
  void readsTheCompletedExampleOfTheContract() {
    TransferNotificationMessage message = reader.read(COMPLETED);

    assertThat(message.transferId()).isEqualTo(TRANSFER_ID);
    assertThat(message.sourceIban()).isEqualTo(SOURCE);
    assertThat(message.destinationIban()).isEqualTo(DESTINATION);
    assertThat(message.amount()).isEqualByComparingTo(new BigDecimal("100.25"));
    assertThat(message.currency()).isEqualTo("EUR");
    assertThat(message.status()).isEqualTo("COMPLETED");
    assertThat(message.reasons()).isNull();
    assertThat(message.occurredAt()).isEqualTo("2026-10-13T09:30:00Z");
  }

  @Test
  void readsTheReasonsOfARejectedMessageInOrder() {
    String rejected = COMPLETED.replace("\"status\":\"COMPLETED\"",
        "\"status\":\"REJECTED\",\"reasons\":[\"first\",\"second\"]");

    assertThat(reader.read(rejected).reasons()).containsExactly("first", "second");
  }

  @ParameterizedTest
  @ValueSource(strings = {"12345678901234567.89", "0.1"})
  void readsTheAmountAsAnExactBigDecimal(String amount) {
    String json = COMPLETED.replace("100.25", amount);

    BigDecimal read = reader.read(json).amount();

    assertThat(read).isEqualByComparingTo(new BigDecimal(amount));
    assertThat(read.toPlainString()).isEqualTo(amount);
  }

  @Test
  void keepsTheScaleOfAnAmountWrittenWithTrailingZeros() {
    BigDecimal read = reader.read(COMPLETED.replace("100.25", "20000.00")).amount();

    assertThat(read).isEqualByComparingTo(new BigDecimal("20000"));
    assertThat(read.scale()).isEqualTo(2);
  }

  @Test
  void ignoresUnknownFieldsIncludingNestedObjects() {
    String json = COMPLETED.replace("\"currency\"",
        "\"extra\":{\"a\":[1,2,{\"b\":null}]},\"other\":\"x\",\"currency\"");

    TransferNotificationMessage message = reader.read(json);

    assertThat(message.transferId()).isEqualTo(TRANSFER_ID);
    assertThat(message.currency()).isEqualTo("EUR");
  }

  @Test
  void leavesAnAbsentFieldNull() {
    TransferNotificationMessage message = reader.read("{}");

    assertThat(message.transferId()).isNull();
    assertThat(message.sourceIban()).isNull();
    assertThat(message.destinationIban()).isNull();
    assertThat(message.amount()).isNull();
    assertThat(message.currency()).isNull();
    assertThat(message.status()).isNull();
    assertThat(message.reasons()).isNull();
    assertThat(message.occurredAt()).isNull();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "null"})
  void rejectsAnEmptyOrNullValueWithAMessageIsEmpty(String json) {
    assertThatThrownBy(() -> reader.read(json))
        .isInstanceOf(InvalidMessageFormatException.class)
        .hasMessage("message is empty");
  }

  @Test
  void rejectsMalformedJsonWithAFixedMessage() {
    assertThatThrownBy(() -> reader.read("{not json"))
        .isInstanceOf(InvalidMessageFormatException.class)
        .hasMessage("malformed JSON");
  }

  @ParameterizedTest
  @ValueSource(strings = {"[]", "\"abc\"", "42"})
  void rejectsARootThatIsNotAnObject(String json) {
    assertThatThrownBy(() -> reader.read(json))
        .isInstanceOf(InvalidMessageFormatException.class)
        .hasMessageMatching("malformed JSON|invalid field type");
  }

  @Test
  void rejectsTextAfterTheJsonDocument() {
    assertThatThrownBy(() -> reader.read(COMPLETED + " trailing"))
        .isInstanceOf(InvalidMessageFormatException.class)
        .hasMessageMatching("malformed JSON|invalid field type");
    assertThatThrownBy(() -> reader.read(COMPLETED + "{}"))
        .isInstanceOf(InvalidMessageFormatException.class)
        .hasMessageMatching("malformed JSON|invalid field type");
  }

  @ParameterizedTest
  @MethodSource("wrongTypeReplacements")
  void rejectsAFieldWithTheWrongJsonTypeAsInvalidFieldType(String from, String to) {
    String json = COMPLETED.replace(from, to);
    assertThat(json).isNotEqualTo(COMPLETED);

    assertThatThrownBy(() -> reader.read(json))
        .isInstanceOf(InvalidMessageFormatException.class)
        .hasMessage("invalid field type");
  }

  static Stream<Arguments> wrongTypeReplacements() {
    return Stream.of(
        Arguments.of("\"amount\":100.25", "\"amount\":\"100.25\""),
        Arguments.of("\"amount\":100.25", "\"amount\":\"abc\""),
        Arguments.of("\"amount\":100.25", "\"amount\":true"),
        Arguments.of("\"amount\":100.25", "\"amount\":[1]"),
        Arguments.of("\"transferId\":\"" + TRANSFER_ID + "\"", "\"transferId\":123"),
        Arguments.of("\"currency\":\"EUR\"", "\"currency\":1.5"),
        Arguments.of("\"currency\":\"EUR\"", "\"currency\":false"),
        Arguments.of("\"status\":\"COMPLETED\"", "\"status\":{}"),
        Arguments.of("\"status\":\"COMPLETED\"", "\"status\":\"COMPLETED\",\"reasons\":\"x\""),
        Arguments.of("\"status\":\"COMPLETED\"", "\"status\":\"COMPLETED\",\"reasons\":[1]"),
        Arguments.of("\"occurredAt\":\"2026-10-13T09:30:00Z\"", "\"occurredAt\":1760347800"));
  }

  @Test
  void keepsIbansOutOfTheMessageOfAnUnreadableDocument() {
    String unreadable = COMPLETED.substring(0, COMPLETED.length() - 20);

    assertThatThrownBy(() -> reader.read(unreadable))
        .isInstanceOf(InvalidMessageFormatException.class)
        .satisfies(e -> {
          assertThat(e.getMessage()).doesNotContain(SOURCE).doesNotContain(DESTINATION)
              .doesNotContain("0542811101").doesNotContain("100.25");
          assertThat(e.getCause()).isNull();
        });
  }

  @Test
  void keepsIbansOutOfTheMessageOfAWrongTypeAndLeavesNoCause() {
    String json = COMPLETED.replace("\"amount\":100.25", "\"amount\":\"100.25\"");

    assertThatThrownBy(() -> reader.read(json))
        .isInstanceOf(InvalidMessageFormatException.class)
        .satisfies(e -> {
          assertThat(e.getMessage()).doesNotContain(SOURCE).doesNotContain(DESTINATION)
              .doesNotContain("0542811101").doesNotContain("100.25");
          assertThat(e.getCause()).isNull();
        });
  }

  @Test
  void keepsANullItemOfTheReasonsListWithoutFailing() {
    String json = COMPLETED.replace("\"status\":\"COMPLETED\"",
        "\"status\":\"REJECTED\",\"reasons\":[\"a\",null]");

    List<String> reasons = reader.read(json).reasons();

    assertThat(reasons).isEqualTo(Arrays.asList("a", null));
  }
}
