package com.bank.notification.domain;

import com.bank.notification.domain.exception.InvalidNotificationException;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

public record Money(BigDecimal amount, String currency) {

  private static final int MAX_SCALE = 2;
  private static final Pattern CURRENCY_FORMAT = Pattern.compile("[A-Z]{3}");

  public Money {
    if (amount == null) {
      throw new InvalidNotificationException("amount must not be null");
    }
    if (currency == null) {
      throw new InvalidNotificationException("currency must not be null");
    }
    if (amount.signum() <= 0) {
      throw new InvalidNotificationException("amount must be greater than zero");
    }
    if (amount.stripTrailingZeros().scale() > MAX_SCALE) {
      throw new InvalidNotificationException(
          "amount must have at most " + MAX_SCALE + " decimal places");
    }
    if (!CURRENCY_FORMAT.matcher(currency).matches()) {
      throw new InvalidNotificationException("currency must be 3 uppercase letters");
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
    // stripTrailingZeros avoids different hashes for the same value at different scales.
    // The amount is always greater than zero, so the zero special case does not apply.
    return Objects.hash(amount.stripTrailingZeros(), currency);
  }
}
