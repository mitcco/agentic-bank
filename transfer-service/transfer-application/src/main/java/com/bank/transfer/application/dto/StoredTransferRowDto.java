package com.bank.transfer.application.dto;

import com.bank.transfer.domain.TransferStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** A stored transfer, internal or external, with whole IBANs and its creation instant. */
public record StoredTransferRowDto(String transferId, TransferTypeEnum type, String sourceIban, String destIban,
                                   BigDecimal amount, String currency, TransferStatusEnum status,
                                   Instant createdAt) {

  public StoredTransferRowDto {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(sourceIban, "sourceIban");
    Objects.requireNonNull(destIban, "destIban");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  /** IBANs and amount are never printed. */
  @Override
  public String toString() {
    return "StoredTransferRowDto[transferId=" + transferId + "]";
  }
}
