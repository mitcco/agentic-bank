package com.bank.transfer.domain;

import com.bank.transfer.domain.exception.InsufficientFundException;
import java.util.Objects;

public record Account(String id, Iban iban, Money money) {

  public Account {
    Objects.requireNonNull(id);
    Objects.requireNonNull(iban);
    Objects.requireNonNull(money);
  }

  public boolean hasIban(Iban other) {
    return iban.equals(other);
  }

  public Account charge(Money other) {
    if (!money.isGreaterOrEqualsThan(other)) {
      throw new InsufficientFundException();
    }
    return new Account(id, iban, money.subtract(other));
  }

  public Account credit(Money other) {
    return new Account(id, iban, money.add(other));
  }
}
