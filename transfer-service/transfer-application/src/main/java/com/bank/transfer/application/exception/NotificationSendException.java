package com.bank.transfer.application.exception;

/**
 * The broker did not acknowledge the notification. Deliberately without cause: the message carries only the
 * transfer id.
 */
public class NotificationSendException extends RuntimeException {

  public NotificationSendException(String transferId) {
    super("Notification not sent for transfer " + transferId);
  }
}
