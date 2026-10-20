package com.bank.transfer.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** One transfer of the list: the counterparty IBAN is masked and the direction is relative to the requested IBAN. */
public record TransferListItemDto(String transferId, String type, String direction, String counterpartyIbanMasked,
                                  BigDecimal amount, String currency, String status, Instant createdAt) {

  public TransferListItemDto {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(counterpartyIbanMasked, "counterpartyIbanMasked");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  /** Counterparty and amount are never printed. */
  @Override
  public String toString() {
    return "TransferListItemDto[transferId=" + transferId + "]";
  }
}
