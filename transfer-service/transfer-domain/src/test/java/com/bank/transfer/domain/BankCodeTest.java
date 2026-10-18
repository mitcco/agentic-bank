package com.bank.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BankCodeTest {

  @Test
  void acceptsFiveDigits() {
    assertThat(new BankCode("05428").value()).isEqualTo("05428");
  }

  @Test
  void acceptsUppercaseLettersAndDigits() {
    assertThat(new BankCode("AB12C").value()).isEqualTo("AB12C");
  }

  @Test
  void equalValuesAreEqual() {
    assertThat(new BankCode("05428")).isEqualTo(new BankCode("05428")).hasSameHashCodeAs(new BankCode("05428"));
    assertThat(new BankCode("05428")).isNotEqualTo(new BankCode("03069"));
  }

  @Test
  void rejectsNull() {
    assertThatThrownBy(() -> new BankCode(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "     ", "   "})
  void rejectsEmptyAndBlank(String value) {
    assertThatThrownBy(() -> new BankCode(value)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0542", "054281"})
  void rejectsFourOrSixCharacters(String value) {
    assertThatThrownBy(() -> new BankCode(value)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsLowercase() {
    assertThatThrownBy(() -> new BankCode("ab12c")).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"05-28", "05 28", "05-281", "0542 "})
  void rejectsSymbolsAndSpaces(String value) {
    assertThatThrownBy(() -> new BankCode(value)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void errorMessageDoesNotRevealTheValue() {
    assertThatThrownBy(() -> new BankCode("xy-99"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("xy-99");
  }
}
