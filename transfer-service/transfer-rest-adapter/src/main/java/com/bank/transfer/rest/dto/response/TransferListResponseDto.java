package com.bank.transfer.rest.dto.response;

import java.util.List;

public record TransferListResponseDto(List<TransferListItemResponseDto> items, int page, int size,
                                      long totalElements, boolean hasNext) {

}
