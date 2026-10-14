package com.bank.notification.mongo.port.out.mongo.impl;

import static java.util.Objects.requireNonNull;

import com.bank.notification.domain.TransferNotification;
import com.bank.notification.mongo.repository.model.TransferNotificationDocument;
import org.bson.types.Decimal128;

import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Maps the domain notification to its MongoDB document.
 */
final class TransferNotificationDocumentMapper {

  TransferNotificationDocument toDocument(TransferNotification notification) {
    requireNonNull(notification);
    return new TransferNotificationDocument(
        notification.transferId(),
        notification.sourceIban().value(),
        notification.destinationIban().value(),
        new Decimal128(notification.amount().amount().setScale(2, RoundingMode.UNNECESSARY)),
        notification.amount().currency(),
        notification.outcome().name(),
        notification.reasons(),
        Date.from(notification.occurredAt().truncatedTo(ChronoUnit.MILLIS)));
  }
}
