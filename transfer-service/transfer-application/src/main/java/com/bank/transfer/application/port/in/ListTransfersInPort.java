package com.bank.transfer.application.port.in;

import com.bank.transfer.application.command.ListTransfersCommand;
import com.bank.transfer.application.dto.TransferPageDto;

public interface ListTransfersInPort {

  /**
   * @throws IllegalArgumentException if a parameter is not valid (fixed message, the value is never repeated)
   */
  TransferPageDto listTransfers(ListTransfersCommand command);
}
