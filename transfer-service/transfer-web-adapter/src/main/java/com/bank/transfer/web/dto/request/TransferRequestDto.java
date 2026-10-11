package com.bank.transfer.web.dto.request;

import java.math.BigDecimal;

public record TransferRequestDto(String sourceIban, String destIban, BigDecimal amount, String currency) {

}
