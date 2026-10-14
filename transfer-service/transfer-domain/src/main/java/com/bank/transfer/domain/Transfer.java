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
    requirePending();
    return new Transfer(id, source, dest, money, TransferStatusEnum.COMPLETED);
  }

  public Transfer reject() {
    requirePending();
    return new Transfer(id, source, dest, money, TransferStatusEnum.REJECTED);
  }

  private void requirePending() {
    if (status != TransferStatusEnum.PENDING) {
      throw new IllegalStateException("Transfer is already " + status);
    }
  }
}
