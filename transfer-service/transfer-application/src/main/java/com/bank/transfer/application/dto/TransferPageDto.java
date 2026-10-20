package com.bank.transfer.application.dto;

import java.util.List;
import java.util.Objects;

public record TransferPageDto(List<TransferListItemDto> items, int page, int size, long totalElements,
                              boolean hasNext) {

  public TransferPageDto {
    Objects.requireNonNull(items, "items");
  }
}
