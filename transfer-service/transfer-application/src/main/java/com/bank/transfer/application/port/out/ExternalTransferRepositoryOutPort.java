package com.bank.transfer.application.port.out;

import com.bank.transfer.domain.Transfer;
import java.time.Instant;
import java.util.List;
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

  /**
   * Returns the ids of the external transfers that are SENT or UNKNOWN and were created before the threshold,
   * the oldest first, at most {@code max}.
   *
   * @throws IllegalArgumentException if {@code max} is less than 1
   */
  List<String> findInProgressIdsCreatedBefore(Instant threshold, int max);
}
