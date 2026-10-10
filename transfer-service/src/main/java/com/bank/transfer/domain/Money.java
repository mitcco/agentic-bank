package com.bank.transfer.domain;

import com.bank.transfer.domain.exception.DifferentCurrencyException;
import java.math.BigDecimal;
import java.util.Objects;

public record Money(BigDecimal amount, String currency) {

  public Money {
    Objects.requireNonNull(amount);
    Objects.requireNonNull(currency);
    if (amount.signum() < 0) {
      throw new IllegalArgumentException("Amount can not be negative");
    }
  }

  public Money add(Money other) {
    requireSameCurrency(other);
    return new Money(amount.add(other.amount), currency);
  }

  public Money subtract(Money other) {
    requireSameCurrency(other);
    return new Money(amount.subtract(other.amount), currency);
  }

  public boolean isGreaterOrEqualsThan(Money other) {
    requireSameCurrency(other);
    return amount.compareTo(other.amount) >= 0;
  }

  private void requireSameCurrency(Money other) {
    if (!other.currency.equals(currency)) {
      throw new DifferentCurrencyException("Different currency");
    }
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Money(BigDecimal otherAmount, String otherCurrency)
        && currency.equals(otherCurrency)
        && amount.compareTo(otherAmount) == 0;
  }

  @Override
  public int hashCode() {
    // stripTrailingZeros is used to avoid conflicts on different scale.
    BigDecimal normalized = amount.signum() == 0 ? BigDecimal.ZERO : amount.stripTrailingZeros();
    return Objects.hash(normalized, currency);
  }
}
