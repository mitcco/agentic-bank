package com.bank.transfer.application.dto;

import java.util.List;
import java.util.Objects;

/** A slice of stored transfers, newest first, with the exact total of the rows matching the criteria. */
public record StoredTransferListDto(List<StoredTransferRowDto> rows, long total) {

  public StoredTransferListDto {
    Objects.requireNonNull(rows, "rows");
  }
}
