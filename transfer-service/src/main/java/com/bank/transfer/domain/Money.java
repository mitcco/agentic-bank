package com.bank.transfer.domain;

import com.bank.transfer.domain.exception.DifferentCurrencyException;
import java.math.BigDecimal;
import java.util.Objects;

public final class Money {

  private final BigDecimal amount;
  private final String currency;

  public Money(BigDecimal amount, String currency) {
    Objects.requireNonNull(amount);
    Objects.requireNonNull(currency);
    if (amount.signum() < 0) {
      throw new IllegalArgumentException("Amount can not be negative");
    }
    this.amount = amount;
    this.currency = currency;
  }

  public Money add(Money other) {
    if (other.currency.equals(currency)) {
      return new Money(amount.add(other.amount), currency);
    }
    throw new DifferentCurrencyException("Different currency");
  }

  public Money subtract(Money other) {
    if (other.currency.equals(currency)) {
      return new Money(amount.subtract(other.amount), currency);
    }
    throw new DifferentCurrencyException("Different currency");
  }

  public boolean isGraterOrEqualsThan(Money other) {
    if (other.currency.equals(currency)) {
      return amount.compareTo(other.amount) >= 0;
    }
    throw new DifferentCurrencyException("Different currency");
  }

  public BigDecimal amount() {
    return amount;
  }

  public String currency() {
    return currency;
  }

}
