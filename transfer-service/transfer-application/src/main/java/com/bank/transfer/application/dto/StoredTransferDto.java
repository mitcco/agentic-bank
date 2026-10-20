package com.bank.transfer.application.dto;

import com.bank.transfer.domain.TransferStatusEnum;
import java.math.BigDecimal;
import java.util.Objects;

/** A transfer as stored, with whole IBANs. The reason may be null. */
public record StoredTransferDto(String transferId, String sourceIban, String destIban, BigDecimal amount,
                                String currency, TransferStatusEnum status, String reason) {

  public StoredTransferDto {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(sourceIban, "sourceIban");
    Objects.requireNonNull(destIban, "destIban");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(status, "status");
  }

  /** IBANs and amount are never printed. */
  @Override
  public String toString() {
    return "StoredTransferDto[transferId=" + transferId + "]";
  }
}
