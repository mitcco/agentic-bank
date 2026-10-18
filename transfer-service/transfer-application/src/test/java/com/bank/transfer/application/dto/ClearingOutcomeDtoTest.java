package com.bank.transfer.application.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClearingOutcomeDtoTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String REASON = "Rejected by clearing house";

  @Test
  void acceptedWithoutReasonIsValid() {
    ClearingOutcomeDto dto = new ClearingOutcomeDto(TRANSFER_ID, ClearingDecisionEnum.ACCEPTED, null);

    assertThat(dto.transferId()).isEqualTo(TRANSFER_ID);
    assertThat(dto.decision()).isEqualTo(ClearingDecisionEnum.ACCEPTED);
    assertThat(dto.reason()).isNull();
  }

  @Test
  void rejectedWithReasonIsValid() {
    ClearingOutcomeDto dto = new ClearingOutcomeDto(TRANSFER_ID, ClearingDecisionEnum.REJECTED, REASON);

    assertThat(dto.decision()).isEqualTo(ClearingDecisionEnum.REJECTED);
    assertThat(dto.reason()).isEqualTo(REASON);
  }

  @Test
  void rejectedWithNullReasonIsRefused() {
    assertThatThrownBy(() -> new ClearingOutcomeDto(TRANSFER_ID, ClearingDecisionEnum.REJECTED, null))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  "})
  void rejectedWithBlankReasonIsRefused(String reason) {
    assertThatThrownBy(() -> new ClearingOutcomeDto(TRANSFER_ID, ClearingDecisionEnum.REJECTED, reason))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"x", "", "  "})
  void acceptedWithReasonIsRefused(String reason) {
    assertThatThrownBy(() -> new ClearingOutcomeDto(TRANSFER_ID, ClearingDecisionEnum.ACCEPTED, reason))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullTransferIdIsRefused() {
    assertThatThrownBy(() -> new ClearingOutcomeDto(null, ClearingDecisionEnum.ACCEPTED, null))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("transferId");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void blankTransferIdIsRefused(String transferId) {
    assertThatThrownBy(() -> new ClearingOutcomeDto(transferId, ClearingDecisionEnum.ACCEPTED, null))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullDecisionIsRefused() {
    assertThatThrownBy(() -> new ClearingOutcomeDto(TRANSFER_ID, null, null))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("decision");
  }
}
