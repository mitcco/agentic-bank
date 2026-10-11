package com.bank.transfer.web.dto.response;

import java.math.BigDecimal;

public record AccountResponseDto(String iban, BigDecimal amount, String currency) {

}
