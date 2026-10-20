package com.bank.transfer.application.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransferDetailsDtoTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String SOURCE_MASKED = "IT60" + "*".repeat(19) + "0001";
  private static final String DEST_MASKED = "IT60" + "*".repeat(19) + "2345";
  private static final BigDecimal AMOUNT = new BigDecimal("100.25");

  private static TransferDetailsDto dtoWithNull(String field) {
    return new TransferDetailsDto(
      "transferId".equals(field) ? null : TRANSFER_ID,
      "sourceIbanMasked".equals(field) ? null : SOURCE_MASKED,
      "destIbanMasked".equals(field) ? null : DEST_MASKED,
      "amount".equals(field) ? null : AMOUNT,
      "currency".equals(field) ? null : "EUR",
      "status".equals(field) ? null : "COMPLETED",
      List.of());
  }

  private static TransferDetailsDto dtoWithReasons(List<String> reasons) {
    return new TransferDetailsDto(TRANSFER_ID, SOURCE_MASKED, DEST_MASKED, AMOUNT, "EUR", "REJECTED", reasons);
  }

  @Test
  void keepsAllTheFields() {
    TransferDetailsDto dto = dtoWithReasons(List.of("A", "B"));

    assertThat(dto.transferId()).isEqualTo(TRANSFER_ID);
    assertThat(dto.sourceIbanMasked()).isEqualTo(SOURCE_MASKED);
    assertThat(dto.destIbanMasked()).isEqualTo(DEST_MASKED);
    assertThat(dto.amount()).isEqualByComparingTo("100.25");
    assertThat(dto.currency()).isEqualTo("EUR");
    assertThat(dto.status()).isEqualTo("REJECTED");
    assertThat(dto.reasons()).containsExactly("A", "B");
  }

  @ParameterizedTest
  @ValueSource(strings = {"transferId", "sourceIbanMasked", "destIbanMasked", "amount", "currency", "status"})
  void aMissingFieldIsRefusedNamingTheField(String field) {
    assertThatThrownBy(() -> dtoWithNull(field))
      .isInstanceOf(NullPointerException.class)
      .hasMessageContaining(field);
  }

  @Test
  void nullReasonsBecomeAnEmptyList() {
    TransferDetailsDto dto = dtoWithReasons(null);

    assertThat(dto.reasons()).isNotNull().isEmpty();
  }

  @Test
  void reasonsAreImmutable() {
    TransferDetailsDto dto = dtoWithReasons(new ArrayList<>(List.of("A")));

    assertThatThrownBy(() -> dto.reasons().add("B")).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void nullReasonsGiveAnImmutableEmptyList() {
    TransferDetailsDto dto = dtoWithReasons(null);

    assertThatThrownBy(() -> dto.reasons().add("B")).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void reasonsAreCopiedSoLaterChangesToTheOriginalListDoNotShowUp() {
    List<String> original = new ArrayList<>(List.of("A"));
    TransferDetailsDto dto = dtoWithReasons(original);

    original.add("B");

    assertThat(dto.reasons()).containsExactly("A");
  }

  @Test
  void toStringShowsTheTransferIdButNeverMaskedIbansNorAmount() {
    String text = dtoWithReasons(List.of("A")).toString();

    assertThat(text).contains(TRANSFER_ID)
      .doesNotContain(SOURCE_MASKED)
      .doesNotContain(DEST_MASKED)
      .doesNotContain("0001")
      .doesNotContain("2345")
      .doesNotContain("100.25");
  }
}
