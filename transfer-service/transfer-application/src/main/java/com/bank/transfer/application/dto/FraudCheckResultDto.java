package com.bank.transfer.application.dto;

import com.bank.transfer.domain.FraudStatusEnum;

public record FraudCheckResultDto(FraudStatusEnum decision, String reason) {

}
