package com.bank.transfer.port.out.fraud.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FraudCheckHttpRequestDtoTest {

  private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String IBAN_1 = "IT60X0542811101000000000001";
  private static final String IBAN_2 = "IT60X0542811101000000000002";

  private static FraudCheckHttpRequestDto dto(String amount) {
    return FraudCheckHttpRequestDto.of(ID, IBAN_1, IBAN_2, new BigDecimal(amount), "EUR");
  }

  @Test
  void h16_toStringShowsOnlyTheTransferId() {
    String text = dto("100.25").toString();

    assertThat(text).contains(ID).doesNotContain(IBAN_1).doesNotContain(IBAN_2).doesNotContain("100.25");
  }

  @Test
  void factoryKeepsTheFieldsOfTheContract() {
    FraudCheckHttpRequestDto dto = dto("100.25");

    assertThat(dto.transferId()).isEqualTo(ID);
    assertThat(dto.sourceIban()).isEqualTo(IBAN_1);
    assertThat(dto.destinationIban()).isEqualTo(IBAN_2);
    assertThat(dto.amount()).isEqualByComparingTo("100.25");
    assertThat(dto.currency()).isEqualTo("EUR");
  }

  @Test
  void negativeScaleIsRemovedSoTheAmountNeverBecomesAnExponent() {
    assertThat(dto("1E+3").amount().scale()).isZero();
    assertThat(dto("1E+3").amount()).isEqualByComparingTo("1000");
  }

  @Test
  void ordinaryScaleIsKept() {
    assertThat(dto("10.10").amount().scale()).isEqualTo(2);
    assertThat(dto("30").amount().scale()).isZero();
  }
}
