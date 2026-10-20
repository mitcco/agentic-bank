package com.bank.transfer.application.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransferListItemDtoTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String COUNTERPARTY_MASKED = "IT60" + "*".repeat(19) + "0001";
  private static final BigDecimal AMOUNT = new BigDecimal("100.25");
  private static final Instant CREATED_AT = Instant.parse("2026-10-19T08:30:00Z");

  private static TransferListItemDto itemWithNull(String field) {
    return new TransferListItemDto(
      "transferId".equals(field) ? null : TRANSFER_ID,
      "type".equals(field) ? null : "EXTERNAL",
      "direction".equals(field) ? null : "OUTGOING",
      "counterpartyIbanMasked".equals(field) ? null : COUNTERPARTY_MASKED,
      "amount".equals(field) ? null : AMOUNT,
      "currency".equals(field) ? null : "EUR",
      "status".equals(field) ? null : "COMPLETED",
      "createdAt".equals(field) ? null : CREATED_AT);
  }

  @Test
  void keepsAllTheFields() {
    TransferListItemDto item = itemWithNull("none");

    assertThat(item.transferId()).isEqualTo(TRANSFER_ID);
    assertThat(item.type()).isEqualTo("EXTERNAL");
    assertThat(item.direction()).isEqualTo("OUTGOING");
    assertThat(item.counterpartyIbanMasked()).isEqualTo(COUNTERPARTY_MASKED);
    assertThat(item.amount()).isEqualByComparingTo("100.25");
    assertThat(item.currency()).isEqualTo("EUR");
    assertThat(item.status()).isEqualTo("COMPLETED");
    assertThat(item.createdAt()).isEqualTo(CREATED_AT);
  }

  @ParameterizedTest
  @ValueSource(strings = {"transferId", "type", "direction", "counterpartyIbanMasked", "amount", "currency",
    "status", "createdAt"})
  void aMissingMandatoryFieldIsRefusedNamingTheField(String field) {
    assertThatThrownBy(() -> itemWithNull(field))
      .isInstanceOf(NullPointerException.class)
      .hasMessageContaining(field);
  }

  @Test
  void toStringShowsTheTransferIdButNeverTheCounterpartyNorTheAmount() {
    String text = itemWithNull("none").toString();

    assertThat(text).contains(TRANSFER_ID)
      .doesNotContain(COUNTERPARTY_MASKED)
      .doesNotContain("0001")
      .doesNotContain("100.25");
  }
}
