package com.bank.fraud.web.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FraudCheckHttpRequestDto(
        @NotNull(message = "must not be null") String transferId,
        @NotNull(message = "must not be null") String sourceIban,
        @NotNull(message = "must not be null") String destinationIban,
        @NotNull(message = "must not be null")
        @JsonDeserialize(using = StrictDecimalDeserializer.class) BigDecimal amount,
        @NotNull(message = "must not be null") String currency) {

    @Override
    public String toString() {
        return "FraudCheckHttpRequestDto[transferId=" + transferId + "]";
    }
}
