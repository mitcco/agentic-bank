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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Simulated clearing house. The outcome never comes from {@link #submit}: it is queued and returned by the next
 * {@link #pollOutcomes()}. In the TIMEOUT modes the instruction is received (its outcome is queued) but the caller
 * gets a timeout, so the result of the submission is uncertain.
 * The outcomes are kept in memory and stay available to queryStatus even after the poll; a restart erases them
 * (intended: a query without an answer means "instruction never received").
 */
public class SimulatedClearingHouseOutPortImpl implements ClearingHouseOutPort {

  public static final String REJECT_REASON = "Rejected by the clearing house";

  private final ConcurrentLinkedQueue<ClearingOutcomeDto> outcomes = new ConcurrentLinkedQueue<>();
  private final ConcurrentHashMap<String, ClearingOutcomeDto> received = new ConcurrentHashMap<>();
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
      case ACCEPT -> enqueue(accepted(id));
      case REJECT -> enqueue(rejected(id));
      case UNAVAILABLE -> throw new ClearingHouseUnavailableException(id);
      case TIMEOUT_THEN_ACCEPT -> {
        enqueue(accepted(id));
        throw new ClearingHouseTimeoutException(id);
      }
      case TIMEOUT_THEN_REJECT -> {
        enqueue(rejected(id));
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

  @Override
  public Optional<ClearingOutcomeDto> queryStatus(String transferId) {
    Objects.requireNonNull(transferId, "transferId");
    return Optional.ofNullable(received.get(transferId));
  }

  private void enqueue(ClearingOutcomeDto outcome) {
    received.put(outcome.transferId(), outcome);
    outcomes.add(outcome);
  }

  private static ClearingOutcomeDto accepted(String transferId) {
    return new ClearingOutcomeDto(transferId, ClearingDecisionEnum.ACCEPTED, null);
  }

  private static ClearingOutcomeDto rejected(String transferId) {
    return new ClearingOutcomeDto(transferId, ClearingDecisionEnum.REJECTED, REJECT_REASON);
  }
}
