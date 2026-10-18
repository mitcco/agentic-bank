package com.bank.transfer.application.port.out;

import com.bank.transfer.application.dto.ClearingInstructionDto;
import com.bank.transfer.application.dto.ClearingOutcomeDto;
import java.util.List;

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
}
