package com.bank.notification.kafka.port.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoggableTransferIdTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String OTHER_ID = "9d1c7e52-4b3a-4f08-8c6d-2a5e1b7f9c04";
  private static final String IBAN = "IT60X0542811101000000000001";

  @Test
  void returnsThePrimaryValueWhenItIsAUuid() {
    assertThat(LoggableTransferId.of(TRANSFER_ID, null)).isEqualTo(TRANSFER_ID);
  }

  @Test
  void prefersThePrimaryUuidOverTheFallbackUuid() {
    assertThat(LoggableTransferId.of(TRANSFER_ID, OTHER_ID)).isEqualTo(TRANSFER_ID);
  }

  @Test
  void usesTheFallbackWhenThePrimaryIsNull() {
    assertThat(LoggableTransferId.of(null, OTHER_ID)).isEqualTo(OTHER_ID);
  }

  @Test
  void usesTheFallbackWhenThePrimaryIsNotAUuid() {
    assertThat(LoggableTransferId.of(IBAN, OTHER_ID)).isEqualTo(OTHER_ID);
  }

  @Test
  void returnsUnknownWhenTheOnlyValueIsAnIban() {
    assertThat(LoggableTransferId.of(IBAN, null)).isEqualTo("unknown");
  }

  @Test
  void returnsUnknownWhenBothValuesAreNotUuids() {
    assertThat(LoggableTransferId.of(IBAN, "free text")).isEqualTo("unknown");
  }

  @Test
  void returnsUnknownForTextWithLineBreaks() {
    assertThat(LoggableTransferId.of("first\r\nsecond", null)).isEqualTo("unknown");
  }

  @Test
  void returnsUnknownForAUuidFollowedByLineBreaks() {
    assertThat(LoggableTransferId.of(TRANSFER_ID + "\r\nforged", null)).isEqualTo("unknown");
    assertThat(LoggableTransferId.of(TRANSFER_ID + "\n", null)).isEqualTo("unknown");
  }

  @Test
  void returnsUnknownForBlankValues() {
    assertThat(LoggableTransferId.of("", "   ")).isEqualTo("unknown");
  }

  @Test
  void returnsUnknownWhenBothValuesAreNull() {
    assertThat(LoggableTransferId.of(null, null)).isEqualTo("unknown");
  }
}
