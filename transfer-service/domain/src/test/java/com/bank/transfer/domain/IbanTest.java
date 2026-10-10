package com.bank.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IbanTest {

  private static final String VALID = "IT60X0542811101000000123456";

  @Test
  void normalizesSpacesAndCase() {
    Iban iban = new Iban(" it60 x054 2811 1010 0000 0123 456 ");

    assertThat(iban.value()).isEqualTo(VALID);
  }

  @Test
  void ibansWithDifferentFormattingAreEqual() {
    Iban a = new Iban(VALID);
    Iban b = new Iban("it60 x054 2811 1010 0000 0123 456");

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
  }

  @Test
  void rejectsNull() {
    assertThatThrownBy(() -> new Iban(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsBlank() {
    assertThatThrownBy(() -> new Iban("   ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsWrongLength() {
    assertThatThrownBy(() -> new Iban("IT60X054281110100000012345"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsInvalidCharacters() {
    assertThatThrownBy(() -> new Iban("IT60-0542811101000000123456"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
