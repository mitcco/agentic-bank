package com.bank.transfer.application.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.domain.TransferStatusEnum;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StoredTransferDtoTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String SOURCE_IBAN = "IT60X0542811101000000000001";
  private static final String DEST_IBAN = "IT60X0306909606100000012345";
  private static final BigDecimal AMOUNT = new BigDecimal("100.2500");

  private static StoredTransferDto dtoWithNull(String field) {
    return new StoredTransferDto(
      "transferId".equals(field) ? null : TRANSFER_ID,
      "sourceIban".equals(field) ? null : SOURCE_IBAN,
      "destIban".equals(field) ? null : DEST_IBAN,
      "amount".equals(field) ? null : AMOUNT,
      "currency".equals(field) ? null : "EUR",
      "status".equals(field) ? null : TransferStatusEnum.COMPLETED,
      "reason".equals(field) ? null : "Some reason");
  }

  @Test
  void keepsAllTheFields() {
    StoredTransferDto dto = new StoredTransferDto(TRANSFER_ID, SOURCE_IBAN, DEST_IBAN, AMOUNT, "EUR",
      TransferStatusEnum.REJECTED, "Some reason");

    assertThat(dto.transferId()).isEqualTo(TRANSFER_ID);
    assertThat(dto.sourceIban()).isEqualTo(SOURCE_IBAN);
    assertThat(dto.destIban()).isEqualTo(DEST_IBAN);
    assertThat(dto.amount()).isEqualByComparingTo("100.25");
    assertThat(dto.currency()).isEqualTo("EUR");
    assertThat(dto.status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(dto.reason()).isEqualTo("Some reason");
  }

  @ParameterizedTest
  @ValueSource(strings = {"transferId", "sourceIban", "destIban", "amount", "currency", "status"})
  void aMissingMandatoryFieldIsRefusedNamingTheField(String field) {
    assertThatThrownBy(() -> dtoWithNull(field))
      .isInstanceOf(NullPointerException.class)
      .hasMessageContaining(field);
  }

  @Test
  void aNullReasonIsAllowed() {
    StoredTransferDto dto = dtoWithNull("reason");

    assertThat(dto.reason()).isNull();
  }

  @Test
  void toStringShowsTheTransferIdButNeverIbansNorAmount() {
    String text = dtoWithNull("reason").toString();

    assertThat(text).contains(TRANSFER_ID)
      .doesNotContain(SOURCE_IBAN)
      .doesNotContain(DEST_IBAN)
      .doesNotContain("100.25");
  }
}
