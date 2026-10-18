package com.bank.transfer.application.dto;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Instruction sent to the clearing house. IBANs and amount are never printed.
 */
public record ClearingInstructionDto(
  String transferId,
  String sourceIban,
  String destIban,
  BigDecimal amount,
  String currency) {

  public ClearingInstructionDto {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(sourceIban, "sourceIban");
    Objects.requireNonNull(destIban, "destIban");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");
  }

  @Override
  public String toString() {
    return "ClearingInstructionDto[transferId=" + transferId + "]";
  }
}
