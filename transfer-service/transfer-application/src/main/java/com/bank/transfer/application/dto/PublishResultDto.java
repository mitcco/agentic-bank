package com.bank.transfer.application.dto;

public record PublishResultDto(int read, int sent, String failedTransferId, PublishFailureStageEnum failureStage) {

  public boolean failed() {
    return failedTransferId != null;
  }
}
