package com.bank.transfer.application.dto;

import java.math.BigDecimal;

public record AccountDto(String iban, BigDecimal amount, String currency) {

}
