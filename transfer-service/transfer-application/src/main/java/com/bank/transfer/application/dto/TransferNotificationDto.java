package com.bank.transfer.application.dto;

import com.bank.transfer.domain.TransferStatusEnum;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record TransferNotificationDto(
  String transferId,
  String sourceIban,
  String destIban,
  BigDecimal amount,
  String currency,
  TransferStatusEnum status,
  List<String> reasons) {

  public TransferNotificationDto {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(sourceIban, "sourceIban");
    Objects.requireNonNull(destIban, "destIban");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(status, "status");
    requireNotBlank(transferId, "transferId");
    requireNotBlank(sourceIban, "sourceIban");
    requireNotBlank(destIban, "destIban");
    requireNotBlank(currency, "currency");
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("amount must be greater than zero");
    }
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
    for (String reason : reasons) {
      if (reason.isBlank()) {
        throw new IllegalArgumentException("reasons must not contain blank entries");
      }
    }
    if (status != TransferStatusEnum.COMPLETED && status != TransferStatusEnum.REJECTED) {
      throw new IllegalArgumentException("status must be COMPLETED or REJECTED");
    }
    if (status == TransferStatusEnum.REJECTED && reasons.isEmpty()) {
      throw new IllegalArgumentException("a REJECTED notification requires at least one reason");
    }
    if (status == TransferStatusEnum.COMPLETED && !reasons.isEmpty()) {
      throw new IllegalArgumentException("a COMPLETED notification must not have reasons");
    }
  }

  private static void requireNotBlank(String value, String field) {
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
