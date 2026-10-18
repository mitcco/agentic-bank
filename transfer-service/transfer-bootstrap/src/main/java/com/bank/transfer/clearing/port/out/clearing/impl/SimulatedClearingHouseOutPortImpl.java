package com.bank.transfer.clearing.port.out.clearing.impl;

import com.bank.transfer.application.dto.ClearingDecisionEnum;
import com.bank.transfer.application.dto.ClearingInstructionDto;
import com.bank.transfer.application.dto.ClearingOutcomeDto;
import com.bank.transfer.application.exception.ClearingHouseTimeoutException;
import com.bank.transfer.application.exception.ClearingHouseUnavailableException;
import com.bank.transfer.application.port.out.ClearingHouseOutPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Simulated clearing house. The outcome never comes from {@link #submit}: it is queued and returned by the next
 * {@link #pollOutcomes()}. In the TIMEOUT modes the instruction is received (its outcome is queued) but the caller
 * gets a timeout, so the result of the submission is uncertain.
 */
public class SimulatedClearingHouseOutPortImpl implements ClearingHouseOutPort {

  public static final String REJECT_REASON = "Rejected by the clearing house";

  private final ConcurrentLinkedQueue<ClearingOutcomeDto> outcomes = new ConcurrentLinkedQueue<>();
  private volatile SimulatedClearingModeEnum mode;

  public SimulatedClearingHouseOutPortImpl(SimulatedClearingModeEnum initialMode) {
    this.mode = Objects.requireNonNull(initialMode, "initialMode");
  }

  public void setMode(SimulatedClearingModeEnum mode) {
    this.mode = Objects.requireNonNull(mode, "mode");
  }

  public SimulatedClearingModeEnum mode() {
    return mode;
  }

  @Override
  public void submit(ClearingInstructionDto instruction) {
    Objects.requireNonNull(instruction, "instruction");
    String id = instruction.transferId();
    switch (mode) {
      case ACCEPT -> outcomes.add(accepted(id));
      case REJECT -> outcomes.add(rejected(id));
      case UNAVAILABLE -> throw new ClearingHouseUnavailableException(id);
      case TIMEOUT_THEN_ACCEPT -> {
        outcomes.add(accepted(id));
        throw new ClearingHouseTimeoutException(id);
      }
      case TIMEOUT_THEN_REJECT -> {
        outcomes.add(rejected(id));
        throw new ClearingHouseTimeoutException(id);
      }
    }
  }

  @Override
  public List<ClearingOutcomeDto> pollOutcomes() {
    List<ClearingOutcomeDto> ready = new ArrayList<>();
    ClearingOutcomeDto outcome;
    while ((outcome = outcomes.poll()) != null) {
      ready.add(outcome);
    }
    return List.copyOf(ready);
  }

  private static ClearingOutcomeDto accepted(String transferId) {
    return new ClearingOutcomeDto(transferId, ClearingDecisionEnum.ACCEPTED, null);
  }

  private static ClearingOutcomeDto rejected(String transferId) {
    return new ClearingOutcomeDto(transferId, ClearingDecisionEnum.REJECTED, REJECT_REASON);
  }
}
