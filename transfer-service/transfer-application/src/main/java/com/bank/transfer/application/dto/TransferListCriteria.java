package com.bank.transfer.application.dto;

import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.TransferStatusEnum;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Validated criteria for the list of transfers of an IBAN. Empty statuses mean all statuses. The bound from is
 * inclusive, the bound to is exclusive; both are optional.
 */
public record TransferListCriteria(Iban iban, TransferDirectionEnum direction, Set<TransferStatusEnum> statuses,
                                   Set<TransferTypeEnum> types, Instant from, Instant to, int offset, int size) {

  public TransferListCriteria {
    Objects.requireNonNull(iban, "iban");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(statuses, "statuses");
    Objects.requireNonNull(types, "types");
  }

  /** The IBAN is never printed. */
  @Override
  public String toString() {
    return "TransferListCriteria[direction=" + direction + ", statuses=" + statuses + ", types=" + types
      + ", from=" + from + ", to=" + to + ", offset=" + offset + ", size=" + size + "]";
  }
}
