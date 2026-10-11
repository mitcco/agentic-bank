package com.bank.fraud.domain;

import com.bank.fraud.domain.exception.InvalidFraudRequestException;

import java.util.List;

public record FraudAssessment(String transferId, FraudStatusEnum status, List<FraudReasonEnum> reasons) {

    public FraudAssessment {
        if (transferId == null || transferId.isBlank()) {
            throw new InvalidFraudRequestException("Transfer id must not be empty");
        }
        if (status == null) {
            throw new InvalidFraudRequestException("Status must not be null");
        }
        if (reasons == null) {
            throw new InvalidFraudRequestException("Reasons must not be null");
        }
        reasons = List.copyOf(reasons);
        if (status == FraudStatusEnum.ALLOWED && !reasons.isEmpty()) {
            throw new InvalidFraudRequestException("An allowed assessment must have no reasons");
        }
        if (status == FraudStatusEnum.REJECTED && reasons.isEmpty()) {
            throw new InvalidFraudRequestException("A rejected assessment must have reasons");
        }
    }

    public static FraudAssessment allowed(String transferId) {
        return new FraudAssessment(transferId, FraudStatusEnum.ALLOWED, List.of());
    }

    public static FraudAssessment rejected(String transferId, List<FraudReasonEnum> reasons) {
        return new FraudAssessment(transferId, FraudStatusEnum.REJECTED, reasons);
    }
}
