package com.bank.notification.domain.exception;

public class InvalidNotificationException extends RuntimeException {

  public InvalidNotificationException(String message) {
    super(message);
  }
}
