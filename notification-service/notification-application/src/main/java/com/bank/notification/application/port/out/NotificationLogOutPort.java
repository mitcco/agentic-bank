package com.bank.notification.application.port.out;

import com.bank.notification.domain.TransferNotification;

/**
 * Log of the notifications.
 */
public interface NotificationLogOutPort {

  /**
   * Records a notification that has already been saved.
   *
   * @param notification the notification to record
   */
  void log(TransferNotification notification);
}
