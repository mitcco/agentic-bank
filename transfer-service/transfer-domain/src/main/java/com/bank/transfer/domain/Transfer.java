package com.bank.transfer.domain;

import java.util.Objects;
import java.util.UUID;

public record Transfer(String id, Iban source, Iban dest, Money money, TransferStatusEnum status) {

  public Transfer {
    Objects.requireNonNull(id);
    Objects.requireNonNull(source);
    Objects.requireNonNull(dest);
    Objects.requireNonNull(money);
    Objects.requireNonNull(status);
    if (money.amount().signum() <= 0) {
      throw new IllegalArgumentException("Transfer amount has to be greater than 0");
    }
    if (money.amount().stripTrailingZeros().scale() > 2) {
      throw new IllegalArgumentException("Transfer amount can have at most 2 decimals");
    }
    if (source.equals(dest)) {
      throw new IllegalArgumentException("Source and destination IBANs can not be the same");
    }
  }

  public static Transfer create(Iban source, Iban dest, Money money) {
    return new Transfer(UUID.randomUUID().toString(), source, dest, money, TransferStatusEnum.PENDING);
  }

  public Transfer complete() {
    requireStatus(TransferStatusEnum.PENDING);
    return new Transfer(id, source, dest, money, TransferStatusEnum.COMPLETED);
  }

  public Transfer reject() {
    requireStatus(TransferStatusEnum.PENDING);
    return new Transfer(id, source, dest, money, TransferStatusEnum.REJECTED);
  }

  public Transfer markSent() {
    requireStatus(TransferStatusEnum.PENDING);
    return new Transfer(id, source, dest, money, TransferStatusEnum.SENT);
  }

  public Transfer markUnknown() {
    requireStatus(TransferStatusEnum.SENT);
    return new Transfer(id, source, dest, money, TransferStatusEnum.UNKNOWN);
  }

  public Transfer settle() {
    requireStatusIn(TransferStatusEnum.SENT, TransferStatusEnum.UNKNOWN);
    return new Transfer(id, source, dest, money, TransferStatusEnum.COMPLETED);
  }

  public Transfer rejectAfterSent() {
    requireStatusIn(TransferStatusEnum.SENT, TransferStatusEnum.UNKNOWN);
    return new Transfer(id, source, dest, money, TransferStatusEnum.REJECTED);
  }

  private void requireStatus(TransferStatusEnum expected) {
    requireStatusIn(expected);
  }

  private void requireStatusIn(TransferStatusEnum... allowed) {
    for (TransferStatusEnum candidate : allowed) {
      if (status == candidate) {
        return;
      }
    }
    throw new IllegalStateException("Transfer is already " + status);
  }
}
