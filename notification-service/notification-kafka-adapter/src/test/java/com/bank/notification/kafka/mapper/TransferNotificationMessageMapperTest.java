package com.bank.notification.kafka.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.notification.application.command.NotifyTransferCommand;
import com.bank.notification.kafka.dto.TransferNotificationMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

class TransferNotificationMessageMapperTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String SOURCE = "IT60X0542811101000000000099";
  private static final String DESTINATION = "IT60X0542811101000000000002";

  private final TransferNotificationMessageMapper mapper = new TransferNotificationMessageMapper();

  @Test
  void mapsEveryFieldOfACompleteMessage() {
    TransferNotificationMessage message = new TransferNotificationMessage(
        TRANSFER_ID, SOURCE, DESTINATION, new BigDecimal("20000.00"), "EUR", "REJECTED",
        List.of("first", "second"), "2026-10-13T09:31:00Z");

    NotifyTransferCommand command = mapper.toCommand(message);

    assertThat(command.transferId()).isEqualTo(TRANSFER_ID);
    assertThat(command.sourceIban()).isEqualTo(SOURCE);
    assertThat(command.destinationIban()).isEqualTo(DESTINATION);
    assertThat(command.amount()).isEqualByComparingTo(new BigDecimal("20000.00"));
    assertThat(command.currency()).isEqualTo("EUR");
    assertThat(command.status()).isEqualTo("REJECTED");
    assertThat(command.reasons()).containsExactly("first", "second");
    assertThat(command.occurredAt()).isEqualTo(Instant.parse("2026-10-13T09:31:00Z"));
  }

  @Test
  void mapsAMessageWithAllFieldsNullToACommandWithNullFieldsAndEmptyReasons() {
    TransferNotificationMessage message =
        new TransferNotificationMessage(null, null, null, null, null, null, null, null);

    NotifyTransferCommand command = mapper.toCommand(message);

    assertThat(command).isNotNull();
    assertThat(command.transferId()).isNull();
    assertThat(command.sourceIban()).isNull();
    assertThat(command.destinationIban()).isNull();
    assertThat(command.amount()).isNull();
    assertThat(command.currency()).isNull();
    assertThat(command.status()).isNull();
    assertThat(command.reasons()).isEmpty();
    assertThat(command.occurredAt()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"completed", "PENDING", " COMPLETED"})
  void keepsTheStatusStringExactlyAsReceived(String status) {
    TransferNotificationMessage message = new TransferNotificationMessage(
        TRANSFER_ID, SOURCE, DESTINATION, BigDecimal.ONE, "EUR", status, null,
        "2026-10-13T09:30:00Z");

    assertThat(mapper.toCommand(message).status()).isEqualTo(status);
  }

  @ParameterizedTest
  @ValueSource(strings = {"13/10/2026", "", "2026-10-13", "yesterday", "2026-10-13 09:30:00"})
  void rejectsAnOccurredAtThatIsNotIso8601WithoutQuotingIt(String occurredAt) {
    TransferNotificationMessage message = new TransferNotificationMessage(
        TRANSFER_ID, SOURCE, DESTINATION, BigDecimal.ONE, "EUR", "COMPLETED", null, occurredAt);

    assertThatThrownBy(() -> mapper.toCommand(message))
        .isInstanceOf(InvalidMessageFormatException.class)
        .hasMessage("occurredAt is not ISO-8601")
        .satisfies(e -> {
          assertThat(e.getCause()).isNull();
          if (!occurredAt.isEmpty()) {
            assertThat(e.getMessage()).doesNotContain(occurredAt);
          }
        });
  }

  @Test
  void rejectsANullMessage() {
    assertThatNullPointerException().isThrownBy(() -> mapper.toCommand(null));
  }
}
