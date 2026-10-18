package com.bank.transfer.clearing.port.out.clearing.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.dto.ClearingDecisionEnum;
import com.bank.transfer.application.dto.ClearingInstructionDto;
import com.bank.transfer.application.dto.ClearingOutcomeDto;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The query of the status of the simulated clearing house: it remembers the outcome of every received instruction,
 * even after the poll; an empty answer means the instruction was never received.
 */
class SimulatedClearingHouseQueryStatusTest {

  private static final String ID_1 = "11111111-1111-4111-8111-111111111111";
  private static final String ID_2 = "22222222-2222-4222-8222-222222222222";
  private static final String SOURCE_IBAN = "IT60X0542811101000000000001";
  private static final String DEST_IBAN = "IT60X0306909606100000012345";

  private static ClearingInstructionDto instruction(String transferId) {
    return new ClearingInstructionDto(transferId, SOURCE_IBAN, DEST_IBAN, new BigDecimal("30"), "EUR");
  }

  private static ClearingOutcomeDto accepted(String id) {
    return new ClearingOutcomeDto(id, ClearingDecisionEnum.ACCEPTED, null);
  }

  private static ClearingOutcomeDto rejected(String id) {
    return new ClearingOutcomeDto(id, ClearingDecisionEnum.REJECTED,
      SimulatedClearingHouseOutPortImpl.REJECT_REASON);
  }

  private static SimulatedClearingHouseOutPortImpl clearingHouse(SimulatedClearingModeEnum mode) {
    return new SimulatedClearingHouseOutPortImpl(mode);
  }

  @Test
  void acceptedOutcomeIsRememberedAfterThePoll() {
    SimulatedClearingHouseOutPortImpl clearingHouse = clearingHouse(SimulatedClearingModeEnum.ACCEPT);
    clearingHouse.submit(instruction(ID_1));
    assertThat(clearingHouse.pollOutcomes()).containsExactly(accepted(ID_1));

    assertThat(clearingHouse.queryStatus(ID_1)).contains(accepted(ID_1));
    assertThat(clearingHouse.queryStatus(ID_1)).contains(accepted(ID_1));
  }

  @Test
  void acceptedOutcomeIsAlsoAnsweredBeforeThePoll() {
    SimulatedClearingHouseOutPortImpl clearingHouse = clearingHouse(SimulatedClearingModeEnum.ACCEPT);

    clearingHouse.submit(instruction(ID_1));

    assertThat(clearingHouse.queryStatus(ID_1)).contains(accepted(ID_1));
  }

  @Test
  void rejectedOutcomeIsRememberedWithTheReason() {
    SimulatedClearingHouseOutPortImpl clearingHouse = clearingHouse(SimulatedClearingModeEnum.REJECT);
    clearingHouse.submit(instruction(ID_1));
    clearingHouse.pollOutcomes();

    assertThat(clearingHouse.queryStatus(ID_1)).contains(rejected(ID_1));
    assertThat(clearingHouse.queryStatus(ID_1).orElseThrow().reason())
      .isEqualTo(SimulatedClearingHouseOutPortImpl.REJECT_REASON);
  }

  @Test
  void timeoutModesRememberTheQueuedOutcome() {
    SimulatedClearingHouseOutPortImpl accepting = clearingHouse(SimulatedClearingModeEnum.TIMEOUT_THEN_ACCEPT);
    SimulatedClearingHouseOutPortImpl rejecting = clearingHouse(SimulatedClearingModeEnum.TIMEOUT_THEN_REJECT);

    assertThatThrownBy(() -> accepting.submit(instruction(ID_1))).isNotNull();
    assertThatThrownBy(() -> rejecting.submit(instruction(ID_2))).isNotNull();

    // the timeout is thrown, yet the instruction was received: the status is known, before and after the poll
    assertThat(accepting.queryStatus(ID_1)).contains(accepted(ID_1));
    assertThat(rejecting.queryStatus(ID_2)).contains(rejected(ID_2));
    accepting.pollOutcomes();
    rejecting.pollOutcomes();
    assertThat(accepting.queryStatus(ID_1)).contains(accepted(ID_1));
    assertThat(rejecting.queryStatus(ID_2)).contains(rejected(ID_2));
  }

  @Test
  void unavailableIsNotFound() {
    SimulatedClearingHouseOutPortImpl clearingHouse = clearingHouse(SimulatedClearingModeEnum.UNAVAILABLE);

    assertThatThrownBy(() -> clearingHouse.submit(instruction(ID_1))).isNotNull();

    assertThat(clearingHouse.queryStatus(ID_1)).isEmpty();
  }

  @Test
  void neverSubmittedIsNotFound() {
    SimulatedClearingHouseOutPortImpl clearingHouse = clearingHouse(SimulatedClearingModeEnum.ACCEPT);
    clearingHouse.submit(instruction(ID_1));

    assertThat(clearingHouse.queryStatus(ID_2)).isEmpty();
  }

  @Test
  void eachInstructionKeepsTheOutcomeOfTheModeInWhichItWasSubmitted() {
    SimulatedClearingHouseOutPortImpl clearingHouse = clearingHouse(SimulatedClearingModeEnum.ACCEPT);
    clearingHouse.submit(instruction(ID_1));
    clearingHouse.setMode(SimulatedClearingModeEnum.REJECT);
    clearingHouse.submit(instruction(ID_2));

    assertThat(clearingHouse.queryStatus(ID_1)).contains(accepted(ID_1));
    assertThat(clearingHouse.queryStatus(ID_2)).contains(rejected(ID_2));
  }

  @Test
  void pollStillReturnsEachOutcomeOnce() {
    SimulatedClearingHouseOutPortImpl clearingHouse = clearingHouse(SimulatedClearingModeEnum.ACCEPT);
    clearingHouse.submit(instruction(ID_1));
    clearingHouse.submit(instruction(ID_2));

    clearingHouse.queryStatus(ID_1);
    clearingHouse.queryStatus(ID_2);

    // the query does not consume the queue
    assertThat(clearingHouse.pollOutcomes()).containsExactly(accepted(ID_1), accepted(ID_2));
    clearingHouse.queryStatus(ID_1);
    assertThat(clearingHouse.pollOutcomes()).isEmpty();
  }

  @Test
  void nullIdIsRefused() {
    SimulatedClearingHouseOutPortImpl clearingHouse = clearingHouse(SimulatedClearingModeEnum.ACCEPT);

    assertThatThrownBy(() -> clearingHouse.queryStatus(null)).isInstanceOf(NullPointerException.class);
  }
}
