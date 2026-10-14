package com.bank.transfer.application.port.out;

public interface NotificationSenderOutPort {

  /**
   * Returns only after the broker has acknowledged the message.
   *
   * @throws com.bank.transfer.application.exception.NotificationSendException if the message is not acknowledged
   */
  void send(String transferId, String payload);
}
