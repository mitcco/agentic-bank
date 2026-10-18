package com.bank.transfer.clearing.port.out.clearing.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.dto.ClearingDecisionEnum;
import com.bank.transfer.application.dto.ClearingInstructionDto;
import com.bank.transfer.application.dto.ClearingOutcomeDto;
import com.bank.transfer.application.exception.ClearingHouseTimeoutException;
import com.bank.transfer.application.exception.ClearingHouseUnavailableException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The simulated clearing house: plain Java, the outcome never comes from submit but only from the next poll.
 */
class SimulatedClearingHouseOutPortImplTest {

  private static final String ID_1 = "11111111-1111-4111-8111-111111111111";
  private static final String ID_2 = "22222222-2222-4222-8222-222222222222";
  private static final String ID_3 = "33333333-3333-4333-8333-333333333333";
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

  @Test
  void pollWithoutSubmissionsIsEmpty() {
    SimulatedClearingHouseOutPortImpl clearingHouse =
      new SimulatedClearingHouseOutPortImpl(SimulatedClearingModeEnum.ACCEPT);

    assertThat(clearingHouse.pollOutcomes()).isEmpty();
  }

  @Test
  void acceptedOutcomeArrivesOnlyAtTheNextPollAndOnce() {
    SimulatedClearingHouseOutPortImpl clearingHouse =
      new SimulatedClearingHouseOutPortImpl(SimulatedClearingModeEnum.ACCEPT);

    clearingHouse.submit(instruction(ID_1));
    List<ClearingOutcomeDto> first = clearingHouse.pollOutcomes();
    List<ClearingOutcomeDto> second = clearingHouse.pollOutcomes();

    assertThat(first).containsExactly(accepted(ID_1));
    assertThat(second).isEmpty();
  }

  @Test
  void rejectModeProducesARejectedOutcomeWithTheFixedReason() {
    SimulatedClearingHouseOutPortImpl clearingHouse =
      new SimulatedClearingHouseOutPortImpl(SimulatedClearingModeEnum.REJECT);

    clearingHouse.submit(instruction(ID_1));

    assertThat(SimulatedClearingHouseOutPortImpl.REJECT_REASON).isEqualTo("Rejected by the clearing house");
    assertThat(clearingHouse.pollOutcomes()).containsExactly(rejected(ID_1));
  }

  @Test
  void unavailableModeThrowsAndQueuesNothing() {
    SimulatedClearingHouseOutPortImpl clearingHouse =
      new SimulatedClearingHouseOutPortImpl(SimulatedClearingModeEnum.UNAVAILABLE);

    assertThatThrownBy(() -> clearingHouse.submit(instruction(ID_1)))
      .isInstanceOfSatisfying(ClearingHouseUnavailableException.class,
        exception -> assertThat(exception.transferId()).isEqualTo(ID_1));

    assertThat(clearingHouse.pollOutcomes()).isEmpty();
  }

  @Test
  void timeoutThenAcceptThrowsTimeoutButQueuesTheAcceptedOutcome() {
    SimulatedClearingHouseOutPortImpl clearingHouse =
      new SimulatedClearingHouseOutPortImpl(SimulatedClearingModeEnum.TIMEOUT_THEN_ACCEPT);

    assertThatThrownBy(() -> clearingHouse.submit(instruction(ID_1)))
      .isInstanceOfSatisfying(ClearingHouseTimeoutException.class,
        exception -> assertThat(exception.transferId()).isEqualTo(ID_1))
      .isNotInstanceOf(ClearingHouseUnavailableException.class);

    assertThat(clearingHouse.pollOutcomes()).containsExactly(accepted(ID_1));
    assertThat(clearingHouse.pollOutcomes()).isEmpty();
  }

  @Test
  void timeoutThenRejectThrowsTimeoutButQueuesTheRejectedOutcome() {
    SimulatedClearingHouseOutPortImpl clearingHouse =
      new SimulatedClearingHouseOutPortImpl(SimulatedClearingModeEnum.TIMEOUT_THEN_REJECT);

    assertThatThrownBy(() -> clearingHouse.submit(instruction(ID_1)))
      .isInstanceOfSatisfying(ClearingHouseTimeoutException.class,
        exception -> assertThat(exception.transferId()).isEqualTo(ID_1))
      .isNotInstanceOf(ClearingHouseUnavailableException.class);

    assertThat(clearingHouse.pollOutcomes()).containsExactly(rejected(ID_1));
    assertThat(clearingHouse.pollOutcomes()).isEmpty();
  }

  @Test
  void outcomesKeepTheSubmissionOrder() {
    SimulatedClearingHouseOutPortImpl clearingHouse =
      new SimulatedClearingHouseOutPortImpl(SimulatedClearingModeEnum.ACCEPT);

    clearingHouse.submit(instruction(ID_1));
    clearingHouse.submit(instruction(ID_2));
    clearingHouse.submit(instruction(ID_3));

    assertThat(clearingHouse.pollOutcomes()).containsExactly(accepted(ID_1), accepted(ID_2), accepted(ID_3));
  }

  @Test
  void changingTheModeAppliesToTheNextSubmission() {
    SimulatedClearingHouseOutPortImpl clearingHouse =
      new SimulatedClearingHouseOutPortImpl(SimulatedClearingModeEnum.ACCEPT);
    assertThat(clearingHouse.mode()).isEqualTo(SimulatedClearingModeEnum.ACCEPT);

    clearingHouse.submit(instruction(ID_1));
    clearingHouse.setMode(SimulatedClearingModeEnum.REJECT);
    clearingHouse.submit(instruction(ID_2));

    assertThat(clearingHouse.mode()).isEqualTo(SimulatedClearingModeEnum.REJECT);
    // the outcome already queued keeps the mode in which it was submitted
    assertThat(clearingHouse.pollOutcomes()).containsExactly(accepted(ID_1), rejected(ID_2));
  }

  @Test
  void nullsAreRefused() {
    assertThatThrownBy(() -> new SimulatedClearingHouseOutPortImpl(null))
      .isInstanceOf(NullPointerException.class);
    SimulatedClearingHouseOutPortImpl clearingHouse =
      new SimulatedClearingHouseOutPortImpl(SimulatedClearingModeEnum.ACCEPT);

    assertThatThrownBy(() -> clearingHouse.setMode(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> clearingHouse.submit(null)).isInstanceOf(NullPointerException.class);
    assertThat(clearingHouse.mode()).isEqualTo(SimulatedClearingModeEnum.ACCEPT);
  }

  @Test
  void polledListIsImmutable() {
    SimulatedClearingHouseOutPortImpl clearingHouse =
      new SimulatedClearingHouseOutPortImpl(SimulatedClearingModeEnum.ACCEPT);
    clearingHouse.submit(instruction(ID_1));

    List<ClearingOutcomeDto> polled = clearingHouse.pollOutcomes();

    assertThatThrownBy(() -> polled.add(accepted(ID_2))).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(polled::clear).isInstanceOf(UnsupportedOperationException.class);
    assertThat(clearingHouse.pollOutcomes()).isEmpty();
  }
}
