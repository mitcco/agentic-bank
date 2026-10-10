package com.bank.transfer.domain;

import com.bank.transfer.domain.exception.InsufficientFundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Account {

  private final String id;
  private final List<Iban> iban = new ArrayList<>();
  private Money money;

  public Account(String id, Iban iban, Money money) {
    this.id = id;
    this.iban.add(iban);
    this.money = money;
  }

  public String getId() {
    return id;
  }

  public List<Iban> getIban() {
    return iban;
  }

  public void setIban(Iban... iban) {
    this.iban.addAll(Arrays.stream(iban).toList());
  }

  public Money getMoney() {
    return money;
  }

  public void setMoney(Money money) {
    this.money = money;
  }

  public void charge(Money other) {
    if (money.isGraterOrEqualsThan(other)) {
      money = money.subtract(other);
    }
    throw new InsufficientFundException();
  }

  public void credit(Money other) {
    money.add(other);
  }
}
