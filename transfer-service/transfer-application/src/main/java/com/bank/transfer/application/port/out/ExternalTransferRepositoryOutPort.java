package com.bank.transfer.application.port.out;

import com.bank.transfer.domain.Transfer;
import java.util.Optional;

public interface ExternalTransferRepositoryOutPort {

  /**
   * Inserts or replaces the external transfer with the same id. The reason may be null.
   */
  void save(Transfer transfer, String reason);

  /**
   * Finds an external transfer and locks its row until the end of the surrounding transaction.
   */
  Optional<Transfer> findByIdForUpdate(String id);
}
