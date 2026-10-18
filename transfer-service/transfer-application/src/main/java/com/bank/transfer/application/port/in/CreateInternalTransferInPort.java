package com.bank.transfer.application.port.in;

import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.TransferResultDto;

/**
 * Internal flow (destination of this bank). Only the router calls it. It does not extend
 * CreateTransferInPort on purpose: there must be a single bean for each port.
 */
public interface CreateInternalTransferInPort {
  TransferResultDto executeTransfer(CreateTransferCommand command);
}
