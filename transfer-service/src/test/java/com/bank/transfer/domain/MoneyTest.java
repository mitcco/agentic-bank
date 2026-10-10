package com.bank.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.domain.exception.DifferentCurrencyException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

  private static Money EUR(String amount) {
    return new Money(new BigDecimal(amount), "EUR");
  }

  @Test
  void equalityIgnoresBigDecimalScale() {
    assertThat(EUR("2.0")).isEqualTo(EUR("2.00")).hasSameHashCodeAs(EUR("2.00"));
  }

  @Test
  void differentAmountsAreNotEqual() {
    assertThat(EUR("2.00")).isNotEqualTo(EUR("2.01"));
  }

  @Test
  void differentCurrenciesAreNotEqual() {
    assertThat(EUR("2.00")).isNotEqualTo(new Money(new BigDecimal("2.00"), "USD"));
  }

  @Test
  void rejectsNegativeAmount() {
    assertThatThrownBy(() -> EUR("-1")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void addsSameCurrency() {
    assertThat(EUR("1.50").add(EUR("2.50"))).isEqualTo(EUR("4"));
  }

  @Test
  void addWithDifferentCurrencyFails() {
    assertThatThrownBy(() -> EUR("1").add(new Money(BigDecimal.ONE, "USD")))
        .isInstanceOf(DifferentCurrencyException.class);
  }
}
