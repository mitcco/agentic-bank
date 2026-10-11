package com.bank.fraud.web.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record ErrorResponseDto(
        String code,
        String message,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> details) {
}
