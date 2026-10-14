package com.bank.notification.application.command;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

class NotifyTransferCommandTest {

  private static final String IBAN_1 = "IT60X0542811101000000000001";
  private static final String IBAN_2 = "IT60X0542811101000000000002";
  private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final Instant OCCURRED_AT = Instant.parse("2026-10-13T09:30:00Z");

  @Test
  void nullReasons_becomeAnEmptyList() {
    NotifyTransferCommand command = new NotifyTransferCommand(
        ID, IBAN_1, IBAN_2, new BigDecimal("100.25"), "EUR", "COMPLETED", null, OCCURRED_AT);

    assertThat(command.reasons()).isEqualTo(List.of());
    assertThat(command.reasons()).isEmpty();
  }

  @Test
  void reasonsWithANullItem_areKeptWithoutValidation() {
    List<String> reasons = Arrays.asList("a", null);

    NotifyTransferCommand command = new NotifyTransferCommand(
        ID, IBAN_1, IBAN_2, new BigDecimal("100.25"), "EUR", "REJECTED", reasons, OCCURRED_AT);

    assertThat(command.reasons()).containsExactly("a", null);
  }

  @Test
  void allFieldsNullExceptReasons_isAcceptedAndAccessorsReturnNull() {
    NotifyTransferCommand command =
        new NotifyTransferCommand(null, null, null, null, null, null, List.of(), null);

    assertThat(command.transferId()).isNull();
    assertThat(command.sourceIban()).isNull();
    assertThat(command.destinationIban()).isNull();
    assertThat(command.amount()).isNull();
    assertThat(command.currency()).isNull();
    assertThat(command.status()).isNull();
    assertThat(command.occurredAt()).isNull();
    assertThat(command.reasons()).isEmpty();
  }

  @Test
  void populatedFields_areReturnedByTheAccessors() {
    List<String> reasons = List.of("Source IBAN is locked");

    NotifyTransferCommand command = new NotifyTransferCommand(
        ID, IBAN_1, IBAN_2, new BigDecimal("100.25"), "EUR", "REJECTED", reasons, OCCURRED_AT);

    assertThat(command.transferId()).isEqualTo(ID);
    assertThat(command.sourceIban()).isEqualTo(IBAN_1);
    assertThat(command.destinationIban()).isEqualTo(IBAN_2);
    assertThat(command.amount()).isEqualByComparingTo("100.25");
    assertThat(command.currency()).isEqualTo("EUR");
    assertThat(command.status()).isEqualTo("REJECTED");
    assertThat(command.reasons()).containsExactly("Source IBAN is locked");
    assertThat(command.occurredAt()).isEqualTo(OCCURRED_AT);
  }
}
