package com.bank.transfer.application.dto;

public record PendingNotificationDto(long id, String transferId, String payload) {

  public PendingNotificationDto {
    if (transferId == null || transferId.isEmpty()) {
      throw new IllegalArgumentException("transferId must not be empty");
    }
    if (payload == null || payload.isEmpty()) {
      throw new IllegalArgumentException("payload must not be empty");
    }
  }

  /** The payload carries IBANs and amounts: it is never printed. */
  @Override
  public String toString() {
    return "PendingNotificationDto[id=" + id + ", transferId=" + transferId + "]";
  }
}
