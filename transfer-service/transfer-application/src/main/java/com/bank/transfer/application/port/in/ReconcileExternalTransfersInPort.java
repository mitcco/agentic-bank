package com.bank.transfer.application.port.in;

import java.time.Instant;

public interface ReconcileExternalTransfersInPort {

  /**
   * Asks the clearing house about the external transfers that are SENT or UNKNOWN and were created before the
   * given instant, and applies the answers. A transfer the clearing house never received is refunded.
   *
   * @param createdBefore only transfers created before this instant are considered
   * @param maxTransfers the maximum number of transfers examined in one call, at least 1
   * @return the number of transfers concluded
   */
  int reconcileStale(Instant createdBefore, int maxTransfers);
}
