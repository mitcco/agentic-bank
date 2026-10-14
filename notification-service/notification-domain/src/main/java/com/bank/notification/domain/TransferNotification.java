package com.bank.notification.domain;

import com.bank.notification.domain.exception.InvalidNotificationException;

import java.time.Instant;
import java.util.List;

public record TransferNotification(
    String transferId,
    Iban sourceIban,
    Iban destinationIban,
    Money amount,
    TransferOutcome outcome,
    List<String> reasons,
    Instant occurredAt) {

  public TransferNotification {
    if (transferId == null || transferId.isBlank()) {
      throw new InvalidNotificationException("transferId must not be null or blank");
    }
    if (sourceIban == null) {
      throw new InvalidNotificationException("source IBAN must not be null");
    }
    if (destinationIban == null) {
      throw new InvalidNotificationException("destination IBAN must not be null");
    }
    if (amount == null) {
      throw new InvalidNotificationException("amount must not be null");
    }
    if (outcome == null) {
      throw new InvalidNotificationException("outcome must not be null");
    }
    if (reasons == null) {
      throw new InvalidNotificationException("reasons must not be null");
    }
    for (String reason : reasons) {
      if (reason == null || reason.isBlank()) {
        throw new InvalidNotificationException("reasons must not contain null or blank items");
      }
    }
    if (outcome == TransferOutcome.REJECTED && reasons.isEmpty()) {
      throw new InvalidNotificationException("reasons must not be empty when outcome is REJECTED");
    }
    if (outcome == TransferOutcome.COMPLETED && !reasons.isEmpty()) {
      throw new InvalidNotificationException("reasons must be empty when outcome is COMPLETED");
    }
    if (occurredAt == null) {
      throw new InvalidNotificationException("occurredAt must not be null");
    }
    reasons = List.copyOf(reasons);
  }

  @Override
  public String toString() {
    return "TransferNotification[transferId=" + transferId
        + ", outcome=" + outcome
        + ", amount=" + amount.amount()
        + ", currency=" + amount.currency()
        + ", reasons=" + reasons
        + ", occurredAt=" + occurredAt + "]";
  }
}
