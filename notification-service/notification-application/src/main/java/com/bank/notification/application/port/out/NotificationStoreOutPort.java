package com.bank.notification.application.port.out;

import com.bank.notification.domain.TransferNotification;

/**
 * Persistence of the notifications.
 */
public interface NotificationStoreOutPort {

  /**
   * Saves the notification. Idempotent by {@code transferId}: a second save with the same
   * {@code transferId} replaces the stored notification, it does not duplicate it.
   *
   * @param notification the notification to save
   */
  void save(TransferNotification notification);
}
