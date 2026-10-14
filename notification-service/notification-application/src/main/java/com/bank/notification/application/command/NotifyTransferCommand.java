package com.bank.notification.application.command;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Input of {@code NotifyTransferInPort}, filled by the mappers of the inbound adapters.
 *
 * <p>A null or missing {@code reasons} becomes an empty list. No field is validated here:
 * the service and the domain do it. {@code status} is the string of the contract
 * ({@code COMPLETED} or {@code REJECTED}, upper case).
 */
public record NotifyTransferCommand(
    String transferId,
    String sourceIban,
    String destinationIban,
    BigDecimal amount,
    String currency,
    String status,
    List<String> reasons,
    Instant occurredAt) {

  public NotifyTransferCommand {
    if (reasons == null) {
      reasons = List.of();
    }
  }
}
