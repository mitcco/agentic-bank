package com.bank.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.notification.domain.exception.InvalidNotificationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

class MoneyTest {

  private static Money eur(String amount) {
    return new Money(new BigDecimal(amount), "EUR");
  }

  @ParameterizedTest
  @ValueSource(strings = {"100", "100.2", "100.25", "100.2500", "2E+4", "1E+2"})
  void acceptsPositiveAmountsWithAtMostTwoSignificantDecimals(String amount) {
    Money money = eur(amount);

    assertThat(money.amount()).isEqualByComparingTo(new BigDecimal(amount));
    assertThat(money.currency()).isEqualTo("EUR");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "0.00", "-1"})
  void rejectsZeroAndNegativeAmounts(String amount) {
    assertThatThrownBy(() -> eur(amount)).isInstanceOf(InvalidNotificationException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0.001", "1.234", "100.255"})
  void rejectsMoreThanTwoSignificantDecimals(String amount) {
    assertThatThrownBy(() -> eur(amount)).isInstanceOf(InvalidNotificationException.class);
  }

  @Test
  void rejectsNullAmountNamingTheField() {
    assertThatThrownBy(() -> new Money(null, "EUR"))
        .isInstanceOf(InvalidNotificationException.class)
        .hasMessageContaining("amount");
  }

  @Test
  void rejectsNullCurrencyNamingTheField() {
    assertThatThrownBy(() -> new Money(BigDecimal.TEN, null))
        .isInstanceOf(InvalidNotificationException.class)
        .hasMessageContaining("currency");
  }

  @Test
  void acceptsEuroCurrency() {
    assertThat(new Money(BigDecimal.TEN, "EUR").currency()).isEqualTo("EUR");
  }

  @ParameterizedTest
  @ValueSource(strings = {"eur", "EURO", "E1R", "", "EU"})
  void rejectsMalformedCurrency(String currency) {
    assertThatThrownBy(() -> new Money(BigDecimal.TEN, currency))
        .isInstanceOf(InvalidNotificationException.class);
  }

  @Test
  void equalityIgnoresTrailingZeros() {
    assertThat(eur("100.2500")).isEqualTo(eur("100.25")).hasSameHashCodeAs(eur("100.25"));
  }

  @Test
  void equalityIgnoresScientificNotation() {
    assertThat(eur("2E+4")).isEqualTo(eur("20000.00")).hasSameHashCodeAs(eur("20000.00"));
  }

  @Test
  void differentCurrenciesAreNotEqual() {
    assertThat(eur("100")).isNotEqualTo(new Money(new BigDecimal("100"), "USD"));
  }

  @Test
  void differentAmountsAreNotEqual() {
    assertThat(eur("100")).isNotEqualTo(eur("101"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1.234", "-1"})
  void errorMessageDoesNotContainTheAmount(String amount) {
    assertThatThrownBy(() -> eur(amount))
        .isInstanceOf(InvalidNotificationException.class)
        .message()
        .doesNotContain(amount);
  }
}
