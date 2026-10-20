package com.bank.transfer.application.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.domain.TransferStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StoredTransferRowDtoTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String SOURCE_IBAN = "IT60X0542811101000000000141";
  private static final String DEST_IBAN = "IT60X0306909606100000012345";
  private static final BigDecimal AMOUNT = new BigDecimal("100.2500");
  private static final Instant CREATED_AT = Instant.parse("2026-10-19T08:30:00Z");

  private static StoredTransferRowDto rowWithNull(String field) {
    return new StoredTransferRowDto(
      "transferId".equals(field) ? null : TRANSFER_ID,
      "type".equals(field) ? null : TransferTypeEnum.EXTERNAL,
      "sourceIban".equals(field) ? null : SOURCE_IBAN,
      "destIban".equals(field) ? null : DEST_IBAN,
      "amount".equals(field) ? null : AMOUNT,
      "currency".equals(field) ? null : "EUR",
      "status".equals(field) ? null : TransferStatusEnum.COMPLETED,
      "createdAt".equals(field) ? null : CREATED_AT);
  }

  @Test
  void keepsAllTheFields() {
    StoredTransferRowDto row = rowWithNull("none");

    assertThat(row.transferId()).isEqualTo(TRANSFER_ID);
    assertThat(row.type()).isEqualTo(TransferTypeEnum.EXTERNAL);
    assertThat(row.sourceIban()).isEqualTo(SOURCE_IBAN);
    assertThat(row.destIban()).isEqualTo(DEST_IBAN);
    assertThat(row.amount()).isEqualByComparingTo("100.25");
    assertThat(row.currency()).isEqualTo("EUR");
    assertThat(row.status()).isEqualTo(TransferStatusEnum.COMPLETED);
    assertThat(row.createdAt()).isEqualTo(CREATED_AT);
  }

  @ParameterizedTest
  @ValueSource(strings = {"transferId", "type", "sourceIban", "destIban", "amount", "currency", "status",
    "createdAt"})
  void aMissingMandatoryFieldIsRefusedNamingTheField(String field) {
    assertThatThrownBy(() -> rowWithNull(field))
      .isInstanceOf(NullPointerException.class)
      .hasMessageContaining(field);
  }

  @Test
  void toStringShowsTheTransferIdButNeverIbansNorAmount() {
    String text = rowWithNull("none").toString();

    assertThat(text).contains(TRANSFER_ID)
      .doesNotContain(SOURCE_IBAN)
      .doesNotContain(DEST_IBAN)
      .doesNotContain("100.25");
  }
}
