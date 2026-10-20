package com.bank.transfer.rest.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

public record TransferListItemResponseDto(String transferId, String type, String direction,
                                          String counterpartyIbanMasked, BigDecimal amount, String currency,
                                          String transferStatus, Instant createdAt) {

}
