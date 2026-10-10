package com.bank.transfer.domain;

import com.bank.transfer.domain.exception.InsufficientFundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record Account(String id, List<Iban> ibans, Money money) {

  public Account {
    Objects.requireNonNull(id);
    Objects.requireNonNull(money);
    ibans = List.copyOf(ibans);
    if (ibans.isEmpty()) {
      throw new IllegalArgumentException("Account needs at least one IBAN");
    }
    if (ibans.stream().distinct().count() != ibans.size()) {
      throw new IllegalArgumentException("Account can not have duplicate IBANs");
    }
  }

  public Account(String id, Iban iban, Money money) {
    this(id, List.of(iban), money);
  }

  public Account addIban(Iban iban) {
    Objects.requireNonNull(iban);
    List<Iban> updated = new ArrayList<>(ibans);
    updated.add(iban);
    return new Account(id, updated, money);
  }

  public boolean hasIban(Iban iban) {
    return ibans.contains(iban);
  }

  public Account charge(Money other) {
    if (!money.isGreaterOrEqualsThan(other)) {
      throw new InsufficientFundException();
    }
    return new Account(id, ibans, money.subtract(other));
  }

  public Account credit(Money other) {
    return new Account(id, ibans, money.add(other));
  }
}
