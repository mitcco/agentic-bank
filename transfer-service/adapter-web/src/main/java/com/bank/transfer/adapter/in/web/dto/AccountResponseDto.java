package com.bank.transfer.adapter.in.web.dto;

import java.math.BigDecimal;

public record AccountResponseDto(String iban, BigDecimal amount, String currency) {

}
