package com.bank.transfer.application.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** A transfer as returned by a read: the IBANs are already masked. */
public record TransferDetailsDto(String transferId, String sourceIbanMasked, String destIbanMasked,
                                 BigDecimal amount, String currency, String status, List<String> reasons) {

  public TransferDetailsDto {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(sourceIbanMasked, "sourceIbanMasked");
    Objects.requireNonNull(destIbanMasked, "destIbanMasked");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(status, "status");
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }

  /** IBANs and amount are never printed. */
  @Override
  public String toString() {
    return "TransferDetailsDto[transferId=" + transferId + "]";
  }
}
