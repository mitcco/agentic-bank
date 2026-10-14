package com.bank.transfer.application.port.out;

import com.bank.transfer.application.dto.PendingNotificationDto;
import java.util.List;

public interface PendingNotificationOutPort {

  /**
   * At most {@code maxRows} rows, in increasing id order.
   *
   * <p>An invalid row (empty transfer_id or empty payload) makes the whole batch fail with
   * {@code InvalidOutboxRowException} carrying the id of the row: no partial result is returned. The caller (the
   * scheduler) handles it by logging only that id.
   */
  List<PendingNotificationDto> findPending(int maxRows);

  /** Deletes the row in a transaction of its own. Idempotent: a missing row is not an error. */
  void delete(long id);
}
