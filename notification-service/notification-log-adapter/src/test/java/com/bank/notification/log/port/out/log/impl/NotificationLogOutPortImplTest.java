package com.bank.notification.log.port.out.log.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.bank.notification.domain.Iban;
import com.bank.notification.domain.Money;
import com.bank.notification.domain.TransferNotification;
import com.bank.notification.domain.TransferOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

class NotificationLogOutPortImplTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String SOURCE = "IT60X0542811101000000000001";
  private static final String DESTINATION = "IT60X0542811101000000000002";
  private static final String REJECTED_SOURCE = "IT60X0542811101000000000099";
  private static final Instant COMPLETED_AT = Instant.parse("2026-10-13T09:30:00Z");
  private static final Instant REJECTED_AT = Instant.parse("2026-10-13T09:31:00Z");

  private static final String COMPLETED_LINE =
      "Transfer notification transferId=3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93"
          + " outcome=COMPLETED amount=100.25 currency=EUR occurredAt=2026-10-13T09:30:00Z";
  private static final String REJECTED_LINE =
      "Transfer notification transferId=3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93"
          + " outcome=REJECTED amount=20000.00 currency=EUR occurredAt=2026-10-13T09:31:00Z"
          + " reasons=[\"Amount must not exceed 10000 EUR\", \"Source IBAN is locked\"]";

  private RecordingLogger logger;
  private NotificationLogOutPortImpl port;

  @BeforeEach
  void setUp() {
    logger = new RecordingLogger();
    port = new NotificationLogOutPortImpl(logger);
  }

  @Test
  void writesOneInfoLineForCompletedNotificationWithoutReasons() {
    port.log(completed(new BigDecimal("100.25"), TRANSFER_ID, COMPLETED_AT));

    assertThat(logger.events()).hasSize(1);
    RecordingLogger.Event event = logger.events().get(0);
    assertThat(event.level()).isEqualTo(Level.INFO);
    assertThat(event.message()).isEqualTo(COMPLETED_LINE);
    assertThat(event.message()).doesNotContain("reasons");
    assertThat(event.throwable()).isNull();
  }

  @Test
  void writesOneInfoLineForRejectedNotificationWithTheTwoContractReasons() {
    port.log(rejected(TRANSFER_ID, List.of("Amount must not exceed 10000 EUR",
        "Source IBAN is locked")));

    assertThat(logger.events()).hasSize(1);
    assertThat(logger.events().get(0).level()).isEqualTo(Level.INFO);
    assertThat(logger.events().get(0).message()).isEqualTo(REJECTED_LINE);
  }

  @Test
  void writesReasonsInTheOrderOfTheList() {
    port.log(rejected(TRANSFER_ID, List.of("third", "first", "second")));

    assertThat(logger.events()).hasSize(1);
    assertThat(logger.events().get(0).message())
        .endsWith("reasons=[\"third\", \"first\", \"second\"]");
  }

  @Test
  void neverWritesAnIbanForCompletedRejectedOrMultiReasonNotifications() {
    port.log(completed(new BigDecimal("100.25"), TRANSFER_ID, COMPLETED_AT));
    port.log(rejected(TRANSFER_ID, List.of("Amount must not exceed 10000 EUR",
        "Source IBAN is locked")));
    port.log(rejected(TRANSFER_ID, List.of("a", "b", "c")));

    assertThat(logger.events()).hasSize(3);
    for (RecordingLogger.Event event : logger.events()) {
      assertThat(event.message())
          .doesNotContain(SOURCE)
          .doesNotContain(DESTINATION)
          .doesNotContain(REJECTED_SOURCE)
          .doesNotContain("0542811101")
          .doesNotContain("IT60X");
    }
  }

  @ParameterizedTest(name = "amount {0} is written as {1}")
  @CsvSource({
      "2E+4, 20000.00",
      "100.5, 100.50",
      "20000.00, 20000.00",
      "1.000, 1.00"
  })
  void writesAmountAlwaysWithTwoDecimalsAndNoScientificNotation(String amount, String expected) {
    port.log(completed(new BigDecimal(amount), TRANSFER_ID, COMPLETED_AT));

    assertThat(logger.events()).hasSize(1);
    String message = logger.events().get(0).message();
    assertThat(message).contains(" amount=" + expected + " currency=EUR ");
    assertThat(message).doesNotContain("E+");
  }

  @Test
  void writesOccurredAtWithMillisecondsWhenPresent() {
    port.log(completed(new BigDecimal("100.25"), TRANSFER_ID,
        Instant.parse("2026-10-13T09:30:00.123Z")));

    assertThat(logger.events().get(0).message()).endsWith("occurredAt=2026-10-13T09:30:00.123Z");
  }

  @Test
  void escapesLineBreaksAndTabsInReasonsSoTheLineStaysSingle() {
    port.log(rejected(TRANSFER_ID, List.of("a\r\nTransfer notification transferId=fake",
        "tab\there")));

    assertThat(logger.events()).hasSize(1);
    String message = logger.events().get(0).message();
    assertThat(message).doesNotContain("\r").doesNotContain("\n").doesNotContain("\t");
    assertThat(message).contains("\"a\\r\\nTransfer notification transferId=fake\"");
    assertThat(message).contains("\"tab\\there\"");
  }

  @Test
  void escapesQuotesBackslashesAndControlCharactersInReasons() {
    port.log(rejected(TRANSFER_ID, List.of("say \"hi\" \\ and \u0007 bell")));

    assertThat(logger.events()).hasSize(1);
    String message = logger.events().get(0).message();
    assertThat(message).endsWith("reasons=[\"say \\\"hi\\\" \\\\ and \\u0007 bell\"]");
    assertThat(message).doesNotContain("\u0007");
  }

  @Test
  void escapesLineBreaksInTransferId() {
    port.log(completed(new BigDecimal("100.25"), "abc\ndef", COMPLETED_AT));

    assertThat(logger.events()).hasSize(1);
    String message = logger.events().get(0).message();
    assertThat(message).doesNotContain("\n").doesNotContain("\r");
    assertThat(message).contains("transferId=abc\\ndef outcome=COMPLETED");
  }

  @Test
  void masksAnIbanFoundInsideAReason() {
    port.log(rejected(TRANSFER_ID, List.of("Source IT60X0542811101000000000099 locked")));

    assertThat(logger.events()).hasSize(1);
    String message = logger.events().get(0).message();
    assertThat(message).contains("Source [IBAN] locked");
    assertThat(message).doesNotContain("0542811101");
  }

  @Test
  void doesNotUseTheToStringOfTheNotification() {
    port.log(completed(new BigDecimal("100.25"), TRANSFER_ID, COMPLETED_AT));
    port.log(rejected(TRANSFER_ID, List.of("Source IBAN is locked")));

    assertThat(logger.events()).hasSize(2);
    for (RecordingLogger.Event event : logger.events()) {
      assertThat(event.message()).doesNotContain("TransferNotification[");
    }
  }

  @Test
  void writesOneEventPerNotification() {
    port.log(completed(new BigDecimal("100.25"), TRANSFER_ID, COMPLETED_AT));
    port.log(rejected("other-transfer", List.of("Source IBAN is locked")));

    assertThat(logger.events()).hasSize(2);
    assertThat(logger.events().get(0).message()).contains("transferId=" + TRANSFER_ID);
    assertThat(logger.events().get(1).message()).contains("transferId=other-transfer");
  }

  @Test
  void writesNothingAndDoesNotFailWhenInfoIsDisabled() {
    logger.infoEnabled = false;

    assertThatCode(() -> port.log(completed(new BigDecimal("100.25"), TRANSFER_ID, COMPLETED_AT)))
        .doesNotThrowAnyException();

    assertThat(logger.events()).isEmpty();
    // info(String) is recorded even when INFO is off, so this proves the early return.
    assertThat(logger.infoCalls).isZero();
    assertThat(logger.isInfoEnabledCalls).isPositive();
  }

  @Test
  void writesLineSeparatorParagraphSeparatorAndC1ControlInReasonsAsEscapedSequences() {
    port.log(rejected(TRANSFER_ID, List.of("a\u2028b", "c\u2029d", "e\u0085f")));

    assertThat(logger.events()).hasSize(1);
    String message = logger.events().get(0).message();
    assertThat(message).contains("\"a\\u2028b\"", "\"c\\u2029d\"", "\"e\\u0085f\"");
    assertThat(message)
        .doesNotContain(String.valueOf((char) 0x2028))
        .doesNotContain(String.valueOf((char) 0x2029))
        .doesNotContain(String.valueOf((char) 0x0085));
    assertThat(message).endsWith("reasons=[\"a\\u2028b\", \"c\\u2029d\", \"e\\u0085f\"]");
  }

  @Test
  void writesLineSeparatorParagraphSeparatorAndC1ControlInTransferIdAsEscapedSequences() {
    port.log(rejected("x\u2028y\u2029z\u0085w", List.of("Source IBAN is locked")));

    assertThat(logger.events()).hasSize(1);
    String message = logger.events().get(0).message();
    assertThat(message).contains("transferId=x\\u2028y\\u2029z\\u0085w outcome=REJECTED");
    assertThat(message)
        .doesNotContain(String.valueOf((char) 0x2028))
        .doesNotContain(String.valueOf((char) 0x2029))
        .doesNotContain(String.valueOf((char) 0x0085));
  }

  @Test
  void propagatesTheSameExceptionThrownByTheLogger() {
    RuntimeException failure = new IllegalStateException("appender down");
    logger.toThrow = failure;

    Throwable thrown = catchThrowable(
        () -> port.log(completed(new BigDecimal("100.25"), TRANSFER_ID, COMPLETED_AT)));

    assertThat(thrown).isSameAs(failure);
  }

  @Test
  void rejectsANullNotification() {
    assertThatNullPointerException().isThrownBy(() -> port.log(null));

    assertThat(logger.events()).isEmpty();
  }

  @Test
  void rejectsANullLogger() {
    assertThatNullPointerException()
        .isThrownBy(() -> new NotificationLogOutPortImpl((Logger) null));
  }

  // Shows only that the constructor and LoggerFactory do not throw without an SLF4J provider.
  // It does NOT show that a line is built or written: the NOP logger reports INFO as disabled,
  // so line() never runs here. Line content is covered by the tests with RecordingLogger.
  @Test
  void defaultConstructorWorksWithoutAnSlf4jProvider() {
    NotificationLogOutPortImpl defaultPort = new NotificationLogOutPortImpl();

    assertThatCode(() -> defaultPort.log(completed(new BigDecimal("100.25"), TRANSFER_ID,
        COMPLETED_AT))).doesNotThrowAnyException();
  }

  private static TransferNotification completed(BigDecimal amount, String transferId,
      Instant occurredAt) {
    return new TransferNotification(transferId, new Iban(SOURCE), new Iban(DESTINATION),
        new Money(amount, "EUR"), TransferOutcome.COMPLETED, List.of(), occurredAt);
  }

  private static TransferNotification rejected(String transferId, List<String> reasons) {
    return new TransferNotification(transferId, new Iban(REJECTED_SOURCE), new Iban(DESTINATION),
        new Money(new BigDecimal("20000.00"), "EUR"), TransferOutcome.REJECTED, reasons,
        REJECTED_AT);
  }
}
