package com.bank.transfer.domain;

import java.util.Objects;

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
    if (source.equals(dest)) {
      throw new IllegalArgumentException("Source and destination IBANs can not be the same");
    }
  }
}
