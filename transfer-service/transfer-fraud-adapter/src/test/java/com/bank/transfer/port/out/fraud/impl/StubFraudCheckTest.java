package com.bank.transfer.port.out.fraud.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.domain.FraudStatusEnum;
import com.bank.transfer.domain.Iban;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StubFraudCheckTest {

  private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String FREE_1 = "IT60X0542811101000000000001";
  private static final String FREE_2 = "IT60X0542811101000000000002";
  private static final String BLOCKED_1 = "IT60X0542811101000000000098";
  private static final String BLOCKED_2 = "IT60X0542811101000000000099";

  private final StubFraudCheck stub = new StubFraudCheck(new BigDecimal("10000"),
    Set.of(new Iban(BLOCKED_1), new Iban(BLOCKED_2)));

  private FraudCheckResultDto check(String source, String dest, String amount) {
    return stub.checkFraud(ID, source, dest, new BigDecimal(amount), "EUR");
  }

  @Test
  void amountBelowTheThresholdWithFreeIbansIsAllowedWithoutReasons() {
    FraudCheckResultDto result = check(FREE_1, FREE_2, "100.25");

    assertThat(result.decision()).isEqualTo(FraudStatusEnum.ALLOWED);
    assertThat(result.reasons()).isEmpty();
  }

  @Test
  void amountEqualToTheThresholdIsAllowed() {
    FraudCheckResultDto result = check(FREE_1, FREE_2, "10000.00");

    assertThat(result.decision()).isEqualTo(FraudStatusEnum.ALLOWED);
    assertThat(result.reasons()).isEmpty();
  }

  @Test
  void amountJustOverTheThresholdIsRejectedWithTheContractReason() {
    FraudCheckResultDto result = check(FREE_1, FREE_2, "10000.01");

    assertThat(result.decision()).isEqualTo(FraudStatusEnum.REJECTED);
    assertThat(result.reasons()).containsExactly("Amount must not exceed 10000 EUR");
  }

  @Test
  void blockedSourceIsRejectedWithTheContractReason() {
    FraudCheckResultDto result = check(BLOCKED_1, FREE_2, "10");

    assertThat(result.decision()).isEqualTo(FraudStatusEnum.REJECTED);
    assertThat(result.reasons()).containsExactly("Source IBAN is locked");
  }

  @Test
  void blockedDestinationIsRejectedWithTheContractReason() {
    FraudCheckResultDto result = check(FREE_1, BLOCKED_2, "10");

    assertThat(result.decision()).isEqualTo(FraudStatusEnum.REJECTED);
    assertThat(result.reasons()).containsExactly("Destination IBAN is locked");
  }

  @Test
  void overTheThresholdWithBothIbansBlockedGivesAllThreeReasonsInOrderAmountSourceDestination() {
    FraudCheckResultDto result = check(BLOCKED_1, BLOCKED_2, "10000.01");

    assertThat(result.decision()).isEqualTo(FraudStatusEnum.REJECTED);
    assertThat(result.reasons()).containsExactly(
      "Amount must not exceed 10000 EUR",
      "Source IBAN is locked",
      "Destination IBAN is locked");
  }

  @Test
  void blockedIbanIsRecognisedWhateverTheFormattingOfTheIban() {
    FraudCheckResultDto result = check("it60 x054 2811 1010 0000 0000 098", FREE_2, "10");

    assertThat(result.reasons()).containsExactly("Source IBAN is locked");
  }

  @Test
  void theTransferIdDoesNotChangeTheOutcome() {
    FraudCheckResultDto first = stub.checkFraud("id-1", BLOCKED_1, FREE_2, new BigDecimal("20000"), "EUR");
    FraudCheckResultDto second = stub.checkFraud("another-id", BLOCKED_1, FREE_2, new BigDecimal("20000"), "EUR");

    assertThat(second).isEqualTo(first);
    assertThat(first.decision()).isEqualTo(FraudStatusEnum.REJECTED);
  }
}
