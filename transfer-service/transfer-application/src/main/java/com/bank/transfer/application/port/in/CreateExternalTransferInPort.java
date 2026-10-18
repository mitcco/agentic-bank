package com.bank.transfer.application.port.in;

import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.TransferResultDto;

public interface CreateExternalTransferInPort {
  TransferResultDto executeExternalTransfer(CreateTransferCommand command);
}
