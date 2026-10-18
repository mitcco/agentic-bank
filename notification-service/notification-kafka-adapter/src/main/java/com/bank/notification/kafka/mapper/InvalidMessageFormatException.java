package com.bank.notification.kafka.mapper;

/**
 * The body of a message cannot be read. The message is a fixed text and there is never a cause,
 * because the texts of the JSON library quote fragments of the document.
 */
public final class InvalidMessageFormatException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidMessageFormatException(String message) {
    super(message);
  }
}
