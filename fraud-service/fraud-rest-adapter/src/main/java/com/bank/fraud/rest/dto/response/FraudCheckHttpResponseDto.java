package com.bank.fraud.rest.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record FraudCheckHttpResponseDto(
        String transferId,
        String status,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> reasons) {
}
