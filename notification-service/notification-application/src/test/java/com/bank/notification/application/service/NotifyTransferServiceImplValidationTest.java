package com.bank.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.notification.application.command.NotifyTransferCommand;
import com.bank.notification.domain.exception.InvalidNotificationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

class NotifyTransferServiceImplValidationTest {

  private static final String IBAN_1 = "IT60X0542811101000000000001";
  private static final String IBAN_2 = "IT60X0542811101000000000002";
  private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final Instant OCCURRED_AT = Instant.parse("2026-10-13T09:30:00Z");
  private static final BigDecimal AMOUNT = new BigDecimal("100.25");
  private static final List<String> FORBIDDEN_IN_MESSAGES =
      List.of(IBAN_1, IBAN_2, "0542811101", "100.25", "1.234");

  private final List<String> registry = new ArrayList<>();
  private final FakeNotificationStoreOutPort store = new FakeNotificationStoreOutPort(registry);
  private final FakeNotificationLogOutPort log = new FakeNotificationLogOutPort(registry);
  private final NotifyTransferServiceImpl service = new NotifyTransferServiceImpl(store, log);

  private static NotifyTransferCommand command(
      String id, String source, String destination, BigDecimal amount, String currency,
      String status, List<String> reasons, Instant occurredAt) {
    return new NotifyTransferCommand(
        id, source, destination, amount, currency, status, reasons, occurredAt);
  }

  private static NotifyTransferCommand completedWith(BigDecimal amount, String currency) {
    return command(ID, IBAN_1, IBAN_2, amount, currency, "COMPLETED", null, OCCURRED_AT);
  }

  private static NotifyTransferCommand withStatus(String status) {
    return command(ID, IBAN_1, IBAN_2, AMOUNT, "EUR", status, null, OCCURRED_AT);
  }

  private static NotifyTransferCommand rejectedWith(List<String> reasons) {
    return command(ID, IBAN_1, IBAN_2, AMOUNT, "EUR", "REJECTED", reasons, OCCURRED_AT);
  }

  private static NotifyTransferCommand withNullField(String field) {
    return command(
        field.equals("transferId") ? null : ID,
        field.equals("sourceIban") ? null : IBAN_1,
        field.equals("destinationIban") ? null : IBAN_2,
        field.equals("amount") ? null : AMOUNT,
        field.equals("currency") ? null : "EUR",
        field.equals("status") ? null : "COMPLETED",
        null,
        field.equals("occurredAt") ? null : OCCURRED_AT);
  }

  // Every invalid command of the plan, cases 1 to 10 (case 11 runs on all of them), each with
  // the fragment of the domain message that proves which branch rejects it
  static Stream<Arguments> invalidCommands() {
    return Stream.of(
        Arguments.of("null transferId", withNullField("transferId"),
            "transferId must not be null or blank"),
        Arguments.of("null sourceIban", withNullField("sourceIban"),
            "IBAN must not be null or blank"),
        Arguments.of("null destinationIban", withNullField("destinationIban"),
            "IBAN must not be null or blank"),
        Arguments.of("null amount", withNullField("amount"), "amount must not be null"),
        Arguments.of("null currency", withNullField("currency"), "currency must not be null"),
        Arguments.of("null status", withNullField("status"), "outcome must not be null"),
        Arguments.of("null occurredAt", withNullField("occurredAt"),
            "occurredAt must not be null"),
        Arguments.of("status PENDING", withStatus("PENDING"), "status is not supported"),
        Arguments.of("status lowercase", withStatus("completed"), "status is not supported"),
        Arguments.of("status empty", withStatus(""), "status is not supported"),
        Arguments.of("status with leading space", withStatus(" COMPLETED"),
            "status is not supported"),
        Arguments.of("REJECTED with null reasons", rejectedWith(null),
            "reasons must not be empty when outcome is REJECTED"),
        Arguments.of("REJECTED with empty reasons", rejectedWith(List.of()),
            "reasons must not be empty when outcome is REJECTED"),
        Arguments.of("COMPLETED with a reason",
            command(ID, IBAN_1, IBAN_2, AMOUNT, "EUR", "COMPLETED", List.of("x"), OCCURRED_AT),
            "reasons must be empty when outcome is COMPLETED"),
        Arguments.of("zero amount", completedWith(BigDecimal.ZERO, "EUR"),
            "amount must be greater than zero"),
        Arguments.of("too many decimals", completedWith(new BigDecimal("1.234"), "EUR"),
            "amount must have at most 2 decimal places"),
        Arguments.of("lowercase currency", completedWith(AMOUNT, "eur"),
            "currency must be 3 uppercase letters"),
        Arguments.of("source IBAN of 26 characters",
            command(ID, "IT60X054281110100000000001", IBAN_2, AMOUNT, "EUR", "COMPLETED", null,
                OCCURRED_AT),
            "IBAN must be 27 characters long"),
        Arguments.of("destination IBAN with invalid format",
            command(ID, IBAN_1, "1234X0542811101000000000002", AMOUNT, "EUR", "COMPLETED", null,
                OCCURRED_AT),
            "IBAN has an invalid format"),
        Arguments.of("REJECTED with a null reason", rejectedWith(Arrays.asList("a", null)),
            "reasons must not contain null or blank items"));
  }

  // Cases 1, 3 to 10, and 11 (messages without IBAN or amount), scenarios 5, 6 and 7
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidCommands")
  void invalidCommand_throwsInvalidNotificationExceptionWithoutCallingPorts(
      String description, NotifyTransferCommand invalid, String expectedMessageFragment) {
    assertThatThrownBy(() -> service.notifyTransfer(invalid))
        .isInstanceOf(InvalidNotificationException.class)
        .hasMessageContaining(expectedMessageFragment)
        .satisfies(e -> {
          assertThat(e.getMessage()).isNotNull();
          for (String forbidden : FORBIDDEN_IN_MESSAGES) {
            assertThat(e.getMessage()).doesNotContain(forbidden);
          }
        });
    assertThat(registry).isEmpty();
    assertThat(store.saved()).isEmpty();
    assertThat(log.logged()).isEmpty();
  }

  @Test
  void nullStatus_failsWithOutcomeMustNotBeNull() {
    assertThatThrownBy(() -> service.notifyTransfer(withNullField("status")))
        .isInstanceOf(InvalidNotificationException.class)
        .hasMessage("outcome must not be null");
    assertThat(registry).isEmpty();
  }

  // Scenario 5
  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "completed", "", " COMPLETED"})
  void unsupportedStatus_failsWithFixedMessageWithoutTheReceivedValue(String status) {
    assertThatThrownBy(() -> service.notifyTransfer(withStatus(status)))
        .isInstanceOf(InvalidNotificationException.class)
        .hasMessage("status is not supported");
    assertThat(registry).isEmpty();
    assertThat(store.saved()).isEmpty();
    assertThat(log.logged()).isEmpty();
  }

  @Test
  void rejectedWithNullReasonItem_failsWithInvalidNotificationException() {
    assertThatThrownBy(() -> service.notifyTransfer(rejectedWith(Arrays.asList("a", null))))
        .isInstanceOf(InvalidNotificationException.class);
    assertThat(registry).isEmpty();
  }
}
