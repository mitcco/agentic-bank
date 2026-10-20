package com.bank.transfer.application.port.in;

import com.bank.transfer.application.dto.TransferDetailsDto;

public interface GetTransferInPort {

  /**
   * @throws com.bank.transfer.application.exception.TransferNotFoundException if no transfer has this id
   */
  TransferDetailsDto getTransfer(String transferId);
}
