package com.bank.notification.kafka.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * JSON body of a message of the notification topic, as described by the contract.
 *
 * <p>No validation is done here: fields are kept as received. {@code occurredAt} stays a string
 * and is parsed by the mapper. {@link #toString()} omits the IBANs.
 */
public record TransferNotificationMessage(
    String transferId,
    String sourceIban,
    String destinationIban,
    BigDecimal amount,
    String currency,
    String status,
    List<String> reasons,
    String occurredAt) {

  @Override
  public String toString() {
    return "TransferNotificationMessage[transferId=" + transferId + "]";
  }
}
