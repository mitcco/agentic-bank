package com.bank.transfer.application.dto;

import java.util.List;

public record TransferResultDto(String transferId, String transferStatus, List<String> reasons) {

  public TransferResultDto {
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }
}
