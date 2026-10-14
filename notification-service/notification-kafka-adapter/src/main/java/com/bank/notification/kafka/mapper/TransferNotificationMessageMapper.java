package com.bank.notification.kafka.mapper;

import com.bank.notification.application.command.NotifyTransferCommand;
import com.bank.notification.kafka.dto.TransferNotificationMessage;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Maps a message to the command of the use case, field by field and without defaults. The
 * status is kept as received: the domain judges it.
 */
public final class TransferNotificationMessageMapper {

  /**
   * Builds the command.
   *
   * @param message the message read from the topic
   * @return the command, never null
   * @throws InvalidMessageFormatException if {@code occurredAt} is present and not ISO-8601
   */
  public NotifyTransferCommand toCommand(TransferNotificationMessage message) {
    Objects.requireNonNull(message, "message");
    return new NotifyTransferCommand(
        message.transferId(),
        message.sourceIban(),
        message.destinationIban(),
        message.amount(),
        message.currency(),
        message.status(),
        message.reasons(),
        parseOccurredAt(message.occurredAt()));
  }

  private static Instant parseOccurredAt(String occurredAt) {
    if (occurredAt == null) {
      return null;
    }
    try {
      return Instant.parse(occurredAt);
    } catch (DateTimeParseException e) {
      throw new InvalidMessageFormatException("occurredAt is not ISO-8601");
    }
  }
}
