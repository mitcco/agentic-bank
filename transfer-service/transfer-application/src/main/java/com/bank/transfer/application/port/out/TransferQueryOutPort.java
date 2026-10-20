package com.bank.transfer.application.port.out;

import com.bank.transfer.application.dto.StoredTransferDto;
import java.util.Optional;

public interface TransferQueryOutPort {

  /**
   * Reads an internal or external transfer without locking.
   */
  Optional<StoredTransferDto> findById(String transferId);
}
