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

  @Test
  void bankCodeIsTheAbiAtPositionsSixToTen() {
    assertThat(new Iban(VALID).bankCode()).isEqualTo(new BankCode("05428"));
  }

  @Test
  void bankCodeIsReadAfterNormalization() {
    Iban iban = new Iban(" it60 x054 2811 1010 0000 0123 456 ");

    assertThat(iban.bankCode()).isEqualTo(new BankCode("05428"));
  }

  @Test
  void bankCodeOfAnotherBank() {
    BankCode other = new Iban("IT60X0306909606100000012345").bankCode();

    assertThat(other).isEqualTo(new BankCode("03069"));
    assertThat(other).isNotEqualTo(new BankCode("05428"));
  }

  @Test
  void blockedDemoIbanBelongsToOurBank() {
    assertThat(new Iban("IT60X0542811101000000000099").bankCode()).isEqualTo(new BankCode("05428"));
  }

  @Test
  void maskedKeepsTheFirstAndLastFourCharacters() {
    String masked = new Iban("IT60X0542811101000000000001").masked();

    assertThat(masked).isEqualTo("IT60" + "*".repeat(19) + "0001");
  }

  @Test
  void maskedKeepsTheLengthOf27Characters() {
    assertThat(new Iban("IT60X0542811101000000000001").masked()).hasSize(27);
  }

  @Test
  void maskedIsComputedOnTheNormalizedIban() {
    String masked = new Iban(" it60 x054 2811 1010 0000 0000 001 ").masked();

    assertThat(masked).isEqualTo("IT60" + "*".repeat(19) + "0001");
  }

  @Test
  void maskedNeverContainsTheMiddleOfTheIban() {
    String iban = "IT60X0542811101000000000001";

    String masked = new Iban(iban).masked();

    assertThat(masked).doesNotContain("X05428111010000000000");
    assertThat(masked).doesNotContain(iban.substring(4, 23));
    assertThat(masked).doesNotContain("05428");
  }

  @Test
  void maskedOfAnotherBank() {
    String masked = new Iban("IT60X0306909606100000012345").masked();

    assertThat(masked).isEqualTo("IT60" + "*".repeat(19) + "2345");
  }
}
