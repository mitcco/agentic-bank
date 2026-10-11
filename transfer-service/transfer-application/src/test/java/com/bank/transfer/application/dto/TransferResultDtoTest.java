package com.bank.transfer.application.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransferResultDtoTest {

  @Test
  void nullReasonsBecomeAnEmptyList() {
    TransferResultDto dto = new TransferResultDto("t-1", "COMPLETED", null);

    assertThat(dto.reasons()).isNotNull().isEmpty();
  }

  @Test
  void reasonsAreCopiedAndImmutable() {
    List<String> original = new ArrayList<>(List.of("a", "b"));
    TransferResultDto dto = new TransferResultDto("t-1", "REJECTED", original);

    original.add("c");

    assertThat(dto.reasons()).containsExactly("a", "b");
    assertThatThrownBy(() -> dto.reasons().add("x")).isInstanceOf(UnsupportedOperationException.class);
  }
}
