package com.bank.transfer.application.dto;

import java.util.Objects;

public record ClearingOutcomeDto(String transferId, ClearingDecisionEnum decision, String reason) {

  public ClearingOutcomeDto {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(decision, "decision");
    if (transferId.isBlank()) {
      throw new IllegalArgumentException("transferId must not be blank");
    }
    if (decision == ClearingDecisionEnum.REJECTED && (reason == null || reason.isBlank())) {
      throw new IllegalArgumentException("a REJECTED outcome requires a reason");
    }
    if (decision == ClearingDecisionEnum.ACCEPTED && reason != null) {
      throw new IllegalArgumentException("an ACCEPTED outcome must not have a reason");
    }
  }
}
