package com.bank.transfer.application.dto;

import com.bank.transfer.domain.FraudDecisionEnum;

public record FraudCheckResultDto(FraudDecisionEnum decision, String reason) {

}
