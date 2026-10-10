package com.bank.transfer.adapter.in.web.dto;

import java.math.BigDecimal;

public record TransferRequestDto(String sourceIban, String destIban, BigDecimal amount, String currency) {

}
