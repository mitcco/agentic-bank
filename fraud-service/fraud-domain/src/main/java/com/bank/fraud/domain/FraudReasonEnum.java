package com.bank.fraud.domain;

import com.bank.fraud.domain.exception.InvalidFraudRequestException;

public enum FraudReasonEnum {
    AMOUNT_OVER_THRESHOLD,
    SOURCE_IBAN_LOCKED,
    DESTINATION_IBAN_LOCKED;

    public String message(Money threshold) {
        if (threshold == null) {
            throw new InvalidFraudRequestException("Threshold must not be null");
        }
        return switch (this) {
            case AMOUNT_OVER_THRESHOLD ->
                    "Amount must not exceed " + threshold.amount().stripTrailingZeros().toPlainString() + " EUR";
            case SOURCE_IBAN_LOCKED -> "Source IBAN is locked";
            case DESTINATION_IBAN_LOCKED -> "Destination IBAN is locked";
        };
    }
}
