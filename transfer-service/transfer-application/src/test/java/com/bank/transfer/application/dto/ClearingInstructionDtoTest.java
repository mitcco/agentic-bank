package com.bank.transfer.application.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ClearingInstructionDtoTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String SOURCE_IBAN = "IT60X0542811101000000000001";
  private static final String DEST_IBAN = "IT60X0306909606100000012345";
  private static final BigDecimal AMOUNT = new BigDecimal("100.25");
  private static final String CURRENCY = "EUR";

  @Test
  void keepsAllTheFields() {
    ClearingInstructionDto dto =
      new ClearingInstructionDto(TRANSFER_ID, SOURCE_IBAN, DEST_IBAN, AMOUNT, CURRENCY);

    assertThat(dto.transferId()).isEqualTo(TRANSFER_ID);
    assertThat(dto.sourceIban()).isEqualTo(SOURCE_IBAN);
    assertThat(dto.destIban()).isEqualTo(DEST_IBAN);
    assertThat(dto.amount()).isEqualByComparingTo(AMOUNT);
    assertThat(dto.currency()).isEqualTo(CURRENCY);
  }

  @Test
  void nullFieldsAreRefused() {
    assertThatThrownBy(() -> new ClearingInstructionDto(null, SOURCE_IBAN, DEST_IBAN, AMOUNT, CURRENCY))
      .isInstanceOf(NullPointerException.class).hasMessage("transferId");
    assertThatThrownBy(() -> new ClearingInstructionDto(TRANSFER_ID, null, DEST_IBAN, AMOUNT, CURRENCY))
      .isInstanceOf(NullPointerException.class).hasMessage("sourceIban");
    assertThatThrownBy(() -> new ClearingInstructionDto(TRANSFER_ID, SOURCE_IBAN, null, AMOUNT, CURRENCY))
      .isInstanceOf(NullPointerException.class).hasMessage("destIban");
    assertThatThrownBy(() -> new ClearingInstructionDto(TRANSFER_ID, SOURCE_IBAN, DEST_IBAN, null, CURRENCY))
      .isInstanceOf(NullPointerException.class).hasMessage("amount");
    assertThatThrownBy(() -> new ClearingInstructionDto(TRANSFER_ID, SOURCE_IBAN, DEST_IBAN, AMOUNT, null))
      .isInstanceOf(NullPointerException.class).hasMessage("currency");
  }

  @Test
  void toStringShowsOnlyTheTransferId() {
    ClearingInstructionDto dto =
      new ClearingInstructionDto(TRANSFER_ID, SOURCE_IBAN, DEST_IBAN, AMOUNT, CURRENCY);

    assertThat(dto.toString()).isEqualTo("ClearingInstructionDto[transferId=" + TRANSFER_ID + "]");
  }

  @Test
  void toStringNeverShowsTheIbansNorTheAmount() {
    ClearingInstructionDto dto =
      new ClearingInstructionDto(TRANSFER_ID, SOURCE_IBAN, DEST_IBAN, AMOUNT, CURRENCY);

    assertThat(dto.toString())
      .doesNotContain(SOURCE_IBAN)
      .doesNotContain(DEST_IBAN)
      .doesNotContain("100.25");
  }
}
