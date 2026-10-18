package com.bank.transfer.application.port.out;

import com.bank.transfer.application.dto.ClearingInstructionDto;
import com.bank.transfer.application.dto.ClearingOutcomeDto;
import java.util.List;
import java.util.Optional;

public interface ClearingHouseOutPort {

  /**
   * Sends an instruction to the clearing house.
   *
   * @throws com.bank.transfer.application.exception.ClearingHouseUnavailableException if the instruction
   *   was not received
   * @throws com.bank.transfer.application.exception.ClearingHouseTimeoutException if the outcome is
   *   uncertain: the instruction may have been received and its outcome will arrive, if it arrives,
   *   from {@link #pollOutcomes()}
   */
  void submit(ClearingInstructionDto instruction);

  /**
   * Returns the outcomes that are ready and consumes them: they are not returned again.
   */
  List<ClearingOutcomeDto> pollOutcomes();

  /**
   * Asks the clearing house for the outcome of an instruction. An empty result means that the clearing house
   * confirms it never received the instruction (an authoritative answer), so compensating is safe.
   */
  Optional<ClearingOutcomeDto> queryStatus(String transferId);
}
