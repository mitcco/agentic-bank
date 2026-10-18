package com.bank.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.notification.domain.exception.InvalidNotificationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class IbanTest {

  private static final String VALID = "IT60X0542811101000000000001";

  @Test
  void keepsAValidIbanUnchanged() {
    assertThat(new Iban(VALID).value()).isEqualTo(VALID);
  }

  @Test
  void normalizesCaseAndWhitespace() {
    assertThat(new Iban(" it60 x054 2811 1010 0000 0000 001 ").value()).isEqualTo(VALID);
  }

  @Test
  void ibansWrittenDifferentlyButEquivalentAreEqual() {
    Iban messy = new Iban(" it60 x054 2811 1010 0000 0000 001 ");
    Iban clean = new Iban(VALID);

    assertThat(messy).isEqualTo(clean).hasSameHashCodeAs(clean);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   "})
  void rejectsNullOrBlank(String value) {
    assertThatThrownBy(() -> new Iban(value)).isInstanceOf(InvalidNotificationException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"IT60X054281110100000000001"})
  void rejectsTooShortIban(String value) {
    assertThat(value).hasSize(26);

    assertThatThrownBy(() -> new Iban(value)).isInstanceOf(InvalidNotificationException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"IT60X05428111010000000000011"})
  void rejectsTooLongIban(String value) {
    assertThat(value).hasSize(28);

    assertThatThrownBy(() -> new Iban(value)).isInstanceOf(InvalidNotificationException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "1T60X0542811101000000000001",
      "ITX0X0542811101000000000001",
      "IT60X0542811101000000000-01",
      "IT60X0542811101000000000*01"})
  void rejectsWrongFormatWithRightLength(String value) {
    assertThat(value).hasSize(27);

    assertThatThrownBy(() -> new Iban(value)).isInstanceOf(InvalidNotificationException.class);
  }

  @Test
  void toStringHidesTheIban() {
    String text = new Iban(VALID).toString();

    assertThat(text).isEqualTo("Iban[****]").doesNotContain(VALID).doesNotContain("0542811101");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "IT60X054281110100000000001",
      "IT60X05428111010000000000011",
      "1T60X0542811101000000000001",
      "IT60X0542811101000000000-01"})
  void errorMessageDoesNotContainTheInput(String value) {
    assertThatThrownBy(() -> new Iban(value))
        .isInstanceOf(InvalidNotificationException.class)
        .message()
        .doesNotContain(value)
        .doesNotContain("0542811101");
  }
}
