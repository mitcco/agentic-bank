package com.bank.transfer.application.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.domain.FraudStatusEnum;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FraudCheckResultDtoTest {

  @Test
  void nullReasonsBecomeAnEmptyList() {
    FraudCheckResultDto dto = new FraudCheckResultDto(FraudStatusEnum.ALLOWED, null);

    assertThat(dto.reasons()).isNotNull().isEmpty();
  }

  @Test
  void reasonsAreCopiedSoLaterChangesToTheOriginalListDoNotAffectTheDto() {
    List<String> original = new ArrayList<>(List.of("Source IBAN is locked"));
    FraudCheckResultDto dto = new FraudCheckResultDto(FraudStatusEnum.REJECTED, original);

    original.add("Destination IBAN is locked");

    assertThat(dto.reasons()).containsExactly("Source IBAN is locked");
  }

  @Test
  void reasonsListReturnedByTheDtoIsImmutable() {
    FraudCheckResultDto dto = new FraudCheckResultDto(FraudStatusEnum.REJECTED, new ArrayList<>(List.of("a")));

    assertThatThrownBy(() -> dto.reasons().add("b")).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void reasonsKeepTheirOrder() {
    FraudCheckResultDto dto = new FraudCheckResultDto(FraudStatusEnum.REJECTED, List.of("b", "a", "c"));

    assertThat(dto.reasons()).containsExactly("b", "a", "c");
  }

  @Test
  void nullDecisionIsRefused() {
    assertThatThrownBy(() -> new FraudCheckResultDto(null, List.of()))
      .isInstanceOf(NullPointerException.class);
  }
}
