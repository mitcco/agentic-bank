package com.bank.transfer.application.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PendingNotificationDtoTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String IBAN = "IT60X0542811101000000000001";
  private static final String PAYLOAD = "{\"transferId\":\"" + TRANSFER_ID + "\",\"sourceIban\":\"" + IBAN
    + "\",\"amount\":100.25}";

  @Test
  void keepsIdTransferIdAndPayload() {
    PendingNotificationDto dto = new PendingNotificationDto(7L, TRANSFER_ID, PAYLOAD);

    assertThat(dto.id()).isEqualTo(7L);
    assertThat(dto.transferId()).isEqualTo(TRANSFER_ID);
    assertThat(dto.payload()).isEqualTo(PAYLOAD);
  }

  @Test
  void nullTransferIdIsRejected() {
    assertThatThrownBy(() -> new PendingNotificationDto(1L, null, PAYLOAD))
      .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {""})
  void emptyTransferIdIsRejected(String transferId) {
    assertThatThrownBy(() -> new PendingNotificationDto(1L, transferId, PAYLOAD))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullPayloadIsRejected() {
    assertThatThrownBy(() -> new PendingNotificationDto(1L, TRANSFER_ID, null))
      .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {""})
  void emptyPayloadIsRejected(String payload) {
    assertThatThrownBy(() -> new PendingNotificationDto(1L, TRANSFER_ID, payload))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void toStringNeverShowsThePayloadNorTheIban() {
    String text = new PendingNotificationDto(7L, TRANSFER_ID, PAYLOAD).toString();

    assertThat(text).doesNotContain(PAYLOAD).doesNotContain(IBAN).doesNotContain("100.25");
  }
}
