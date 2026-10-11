package com.bank.transfer.application.dto;

import com.bank.transfer.domain.FraudStatusEnum;
import java.util.List;
import java.util.Objects;

public record FraudCheckResultDto(FraudStatusEnum decision, List<String> reasons) {

  public FraudCheckResultDto {
    Objects.requireNonNull(decision, "decision");
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }
}
