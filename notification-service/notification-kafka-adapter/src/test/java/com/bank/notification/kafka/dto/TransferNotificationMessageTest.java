package com.bank.notification.kafka.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

class TransferNotificationMessageTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String SOURCE = "IT60X0542811101000000000001";
  private static final String DESTINATION = "IT60X0542811101000000000002";

  @Test
  void toStringContainsTheTransferIdAndNoIban() {
    TransferNotificationMessage message = new TransferNotificationMessage(
        TRANSFER_ID, SOURCE, DESTINATION, new BigDecimal("100.25"), "EUR", "COMPLETED",
        List.of("reason"), "2026-10-13T09:30:00Z");

    String text = message.toString();

    assertThat(text).contains(TRANSFER_ID);
    assertThat(text).doesNotContain(SOURCE).doesNotContain(DESTINATION)
        .doesNotContain("0542811101");
  }

  @Test
  void toStringDoesNotFailWhenAllFieldsAreNull() {
    TransferNotificationMessage message =
        new TransferNotificationMessage(null, null, null, null, null, null, null, null);

    assertThat(message.toString()).isNotNull();
  }

  @Test
  void keepsTheValuesWithoutValidationOrCopy() {
    List<String> reasons = new ArrayList<>(List.of("a"));

    TransferNotificationMessage message = new TransferNotificationMessage(
        TRANSFER_ID, SOURCE, DESTINATION, null, null, "anything", reasons, "not a date");

    assertThat(message.status()).isEqualTo("anything");
    assertThat(message.occurredAt()).isEqualTo("not a date");
    assertThat(message.reasons()).isSameAs(reasons);
  }
}
