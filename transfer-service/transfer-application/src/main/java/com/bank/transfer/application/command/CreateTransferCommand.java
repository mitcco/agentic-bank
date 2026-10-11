package com.bank.transfer.application.command;

import java.math.BigDecimal;

public record CreateTransferCommand(String sourceIban, String destIban, BigDecimal amount, String currency) {

}
