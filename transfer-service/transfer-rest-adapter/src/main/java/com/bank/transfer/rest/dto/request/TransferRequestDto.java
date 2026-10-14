package com.bank.transfer.rest.dto.request;

import java.math.BigDecimal;

public record TransferRequestDto(String sourceIban, String destIban, BigDecimal amount, String currency) {

}
