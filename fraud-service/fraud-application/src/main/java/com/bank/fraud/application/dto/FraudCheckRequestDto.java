package com.bank.fraud.application.dto;

import java.math.BigDecimal;

public record FraudCheckRequestDto(
        String transferId,
        String sourceIban,
        String destinationIban,
        BigDecimal amount,
        String currency) {

    @Override
    public String toString() {
        return "FraudCheckRequestDto[transferId=" + transferId + "]";
    }
}
