package com.bank.fraud.application.dto;

import java.util.List;

public record FraudCheckResponseDto(String transferId, String status, List<String> reasons) {

    public FraudCheckResponseDto {
        reasons = List.copyOf(reasons);
    }
}
